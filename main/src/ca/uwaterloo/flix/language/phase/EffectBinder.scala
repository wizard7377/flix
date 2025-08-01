/*
 * Copyright 2023 Jonathan Lindegaard Starup
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.uwaterloo.flix.language.phase

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.Symbol.VarSym
import ca.uwaterloo.flix.language.ast.shared.{BoundBy, ExpPosition, Scope}
import ca.uwaterloo.flix.language.ast.{AtomicOp, LiftedAst, Purity, ReducedAst, SemanticOp, SourceLocation, Symbol}
import ca.uwaterloo.flix.language.dbg.AstPrinter.DebugReducedAst
import ca.uwaterloo.flix.language.phase.jvm.GenExpression
import ca.uwaterloo.flix.util.ParOps
import ca.uwaterloo.flix.util.collection.MapOps

import scala.annotation.tailrec
import scala.collection.mutable

/**
  * This phase transforms the AST such that all effect operations will happen on
  * an empty operand stack in [[GenExpression]].
  *
  * The number of "pc points" must be counted, which is the number of points
  * where a continuation will be used. This includes do operations, all calls
  * except tail recursive self-calls, and try-with expressions.
  *
  * An effect operation is either a Do or an application with a control effect,
  * i.e. an effect that's not just a region or IO. For now all calls are
  * considered to have an effect.
  *
  * Currently, this phase let-binds everything maximally, simplifying the
  * algorithm.
  */
object EffectBinder {

  // We are safe to use the top scope everywhere because we do not use unification in this or future phases.
  private implicit val S: Scope = Scope.Top

  /**
    * Transforms the AST such that effect operations will be run without an
    * operand stack.
    */
  def run(root: LiftedAst.Root)(implicit flix: Flix): ReducedAst.Root = flix.phase("EffectBinder") {
    val newDefs = ParOps.parMapValues(root.defs)(visitDef)
    val newEnums = ParOps.parMapValues(root.enums)(visitEnum)
    val newStructs = ParOps.parMapValues(root.structs)(visitStruct)
    val newEffects = ParOps.parMapValues(root.effects)(visitEffect)
    ReducedAst.Root(newDefs, newEnums, newStructs, newEffects, Set.empty, Nil, root.mainEntryPoint, root.entryPoints, root.sources)
  }

  private sealed trait Binder

  private case class LetBinder(sym: VarSym, exp: ReducedAst.Expr, loc: SourceLocation) extends Binder

  private case class NonBinder(exp: ReducedAst.Expr, loc: SourceLocation) extends Binder

  /**
    * Transforms the [[LiftedAst.Def]] such that effect operations will be run without an
    * operand stack.
    */
  private def visitDef(defn: LiftedAst.Def)(implicit flix: Flix): ReducedAst.Def = defn match {
    case LiftedAst.Def(ann, mod, sym, cparams0, fparams0, exp0, tpe, loc) =>
      val cparams = cparams0.map(visitParam)
      val fparams = fparams0.map(visitParam)
      val lparams = Nil
      val exp = visitExpr(exp0)
      ReducedAst.Def(ann, mod, sym, cparams, fparams, lparams, -1, exp, tpe, ReducedAst.UnboxedType(tpe), loc)
  }

  private def visitEnum(enm: LiftedAst.Enum): ReducedAst.Enum = enm match {
    case LiftedAst.Enum(ann, mod, sym, tparams0, cases0, loc) =>
      val tparams = tparams0.map(param => ReducedAst.TypeParam(param.name, param.sym, param.loc))
      val cases = MapOps.mapValues(cases0)(visitEnumCase)
      ReducedAst.Enum(ann, mod, sym, tparams, cases, loc)
  }

  private def visitEnumCase(caze: LiftedAst.Case): ReducedAst.Case = caze match {
    case LiftedAst.Case(sym, tpes, loc) => ReducedAst.Case(sym, tpes, loc)
  }

  private def visitStruct(struct: LiftedAst.Struct): ReducedAst.Struct = struct match {
    case LiftedAst.Struct(ann, mod, sym, tparams0, fields0, loc) =>
      val tparams = tparams0.map(param => ReducedAst.TypeParam(param.name, param.sym, param.loc))
      val fields = fields0.map(visitStructField)
      ReducedAst.Struct(ann, mod, sym, tparams, fields, loc)
  }

  private def visitStructField(field: LiftedAst.StructField): ReducedAst.StructField = field match {
    case LiftedAst.StructField(sym, tpe, loc) => ReducedAst.StructField(sym, tpe, loc)
  }

  private def visitEffect(e: LiftedAst.Effect): ReducedAst.Effect = e match {
    case LiftedAst.Effect(ann, mod, sym, ops0, loc) =>
      val ops = ops0.map(visitOp)
      ReducedAst.Effect(ann, mod, sym, ops, loc)
  }

  private def visitOp(op: LiftedAst.Op): ReducedAst.Op = op match {
    case LiftedAst.Op(sym, ann, mod, fparams0, tpe, purity, loc) =>
      val fparams = fparams0.map(visitParam)
      ReducedAst.Op(sym, ann, mod, fparams, tpe, purity, loc)
  }

  private def visitParam(p: LiftedAst.FormalParam): ReducedAst.FormalParam = p match {
    case LiftedAst.FormalParam(sym, mod, tpe, loc) =>
      ReducedAst.FormalParam(sym, mod, tpe, loc)
  }

  private def visitJvmMethod(method: LiftedAst.JvmMethod)(implicit flix: Flix): ReducedAst.JvmMethod = method match {
    case LiftedAst.JvmMethod(ident, fparams0, clo0, retTpe, purity, loc) =>
      // JvmMethods are generated as their own functions so let-binding do not
      // span across
      val fparams = fparams0.map(visitParam)
      val clo = visitExpr(clo0)
      ReducedAst.JvmMethod(ident, fparams, clo, retTpe, purity, loc)
  }

  /**
    * Transforms the [[LiftedAst.Expr]] such that effect operations will be run without an
    * operand stack - binding necessary expressions in the returned [[ReducedAst.Expr]].
    */
  private def visitExpr(exp0: LiftedAst.Expr)(implicit flix: Flix): ReducedAst.Expr = exp0 match {
    case LiftedAst.Expr.Cst(_, _, _) =>
      val binders = mutable.ArrayBuffer.empty[Binder]
      val e = visitExprInnerWithBinders(binders)(exp0)
      bindBinders(binders, e)

    case LiftedAst.Expr.Var(_, _, _) =>
      val binders = mutable.ArrayBuffer.empty[Binder]
      val e = visitExprInnerWithBinders(binders)(exp0)
      bindBinders(binders, e)

    case LiftedAst.Expr.ApplyAtomic(_, _, _, _, _) =>
      val binders = mutable.ArrayBuffer.empty[Binder]
      val e = visitExprInnerWithBinders(binders)(exp0)
      bindBinders(binders, e)

    case LiftedAst.Expr.ApplyClo(_, _, _, _, _) =>
      val binders = mutable.ArrayBuffer.empty[Binder]
      val e = visitExprInnerWithBinders(binders)(exp0)
      bindBinders(binders, e)

    case LiftedAst.Expr.ApplyDef(_, _, _, _, _) =>
      val binders = mutable.ArrayBuffer.empty[Binder]
      val e = visitExprInnerWithBinders(binders)(exp0)
      bindBinders(binders, e)

    case LiftedAst.Expr.ApplyOp(_, _, _, _, _) =>
      val binders = mutable.ArrayBuffer.empty[Binder]
      val e = visitExprInnerWithBinders(binders)(exp0)
      bindBinders(binders, e)

    case LiftedAst.Expr.IfThenElse(_, _, _, _, _, _) =>
      val binders = mutable.ArrayBuffer.empty[Binder]
      val e = visitExprInnerWithBinders(binders)(exp0)
      bindBinders(binders, e)

    case LiftedAst.Expr.Branch(exp, branches0, tpe, purity, loc) =>
      val e = visitExpr(exp)
      val branches = MapOps.mapValues(branches0)(visitExpr)
      ReducedAst.Expr.Branch(e, branches, tpe, purity, loc)

    case LiftedAst.Expr.JumpTo(_, _, _, _) =>
      val binders = mutable.ArrayBuffer.empty[Binder]
      val e = visitExprInnerWithBinders(binders)(exp0)
      bindBinders(binders, e)

    case LiftedAst.Expr.Let(sym, exp1, exp2, tpe, purity, loc) =>
      val binders = mutable.ArrayBuffer.empty[Binder]
      val e1 = visitExprInnerWithBinders(binders)(exp1)
      val e2 = visitExpr(exp2)
      val e = ReducedAst.Expr.Let(sym, e1, e2, tpe, purity, loc)
      bindBinders(binders, e)

    case LiftedAst.Expr.Stm(exp1, exp2, tpe, purity, loc) =>
      val binders = mutable.ArrayBuffer.empty[Binder]
      val e1 = visitExprInnerWithBinders(binders)(exp1)
      val e2 = visitExpr(exp2)
      val e = ReducedAst.Expr.Stmt(e1, e2, tpe, purity, loc)
      bindBinders(binders, e)

    case LiftedAst.Expr.Scope(sym, exp, tpe, purity, loc) =>
      val e = visitExpr(exp)
      ReducedAst.Expr.Scope(sym, e, tpe, purity, loc)

    case LiftedAst.Expr.TryCatch(exp, rules, tpe, purity, loc) =>
      val e = visitExpr(exp)
      val rules1 = rules.map {
        case cr => ReducedAst.CatchRule(cr.sym, cr.clazz, visitExpr(cr.exp))
      }
      ReducedAst.Expr.TryCatch(e, rules1, tpe, purity, loc)

    case LiftedAst.Expr.RunWith(exp, effUse, rules, tpe, purity, loc) =>
      val e = visitExpr(exp)
      val rules1 = rules.map {
        case LiftedAst.HandlerRule(symUse, fparams0, body) =>
          val fparams = fparams0.map(visitParam)
          val b = visitExpr(body)
          ReducedAst.HandlerRule(symUse, fparams, b)
      }
      ReducedAst.Expr.RunWith(e, effUse, rules1, ExpPosition.NonTail, tpe, purity, loc)

    case LiftedAst.Expr.NewObject(_, _, _, _, _, _) =>
      val binders = mutable.ArrayBuffer.empty[Binder]
      val e = visitExprInnerWithBinders(binders)(exp0)
      bindBinders(binders, e)
  }

  /**
    * Transforms the [[LiftedAst.Expr]] such that effect operations will be run without an
    * operand stack. The outer-most expression IS NOT let-bound but all
    * sub-expressions will be. `do E(x, y, z)` might be returned.
    *
    * Necessary bindings are added to binders, where the first binder is the
    * outermost one.
    */
  private def visitExprInnerWithBinders(binders: mutable.ArrayBuffer[Binder])(exp0: LiftedAst.Expr)(implicit flix: Flix): ReducedAst.Expr = exp0 match {
    case LiftedAst.Expr.Cst(cst, tpe, loc) =>
      ReducedAst.Expr.Cst(cst, tpe, loc)

    case LiftedAst.Expr.Var(sym, tpe, loc) =>
      ReducedAst.Expr.Var(sym, tpe, loc)

    case LiftedAst.Expr.ApplyAtomic(op@AtomicOp.Binary(SemanticOp.BoolOp.And | SemanticOp.BoolOp.Or), exps, tpe, purity, loc) =>
      // And and Or does not leave the first argument on the stack in genExpression.
      val List(exp1, exp2) = exps
      val e1 = visitExprWithBinders(binders)(exp1)
      val e2 = visitExpr(exp2)
      ReducedAst.Expr.ApplyAtomic(op, List(e1, e2), tpe, purity, loc)

    case LiftedAst.Expr.ApplyAtomic(op, exps, tpe, purity, loc) =>
      val es = exps.map(visitExprWithBinders(binders))
      ReducedAst.Expr.ApplyAtomic(op, es, tpe, purity, loc)

    case LiftedAst.Expr.ApplyClo(exp1, exp2, tpe, purity, loc) =>
      val e1 = visitExprWithBinders(binders)(exp1)
      val e2 = visitExprWithBinders(binders)(exp2)
      ReducedAst.Expr.ApplyClo(e1, e2, ExpPosition.NonTail, tpe, purity, loc)

    case LiftedAst.Expr.ApplyDef(sym, exps, tpe, purity, loc) =>
      val es = exps.map(visitExprWithBinders(binders))
      ReducedAst.Expr.ApplyDef(sym, es, ExpPosition.NonTail, tpe, purity, loc)

    case LiftedAst.Expr.ApplyOp(sym, exps, tpe, purity, loc) =>
      val es = exps.map(visitExprWithBinders(binders))
      ReducedAst.Expr.ApplyOp(sym, es, tpe, purity, loc)

    case LiftedAst.Expr.IfThenElse(exp1, exp2, exp3, tpe, purity, loc) =>
      val e1 = visitExprInnerWithBinders(binders)(exp1)
      val e2 = visitExpr(exp2)
      val e3 = visitExpr(exp3)
      ReducedAst.Expr.IfThenElse(e1, e2, e3, tpe, purity, loc)

    case LiftedAst.Expr.Branch(exp, branches, tpe, purity, loc) =>
      val e = visitExpr(exp)
      val bs = branches.map {
        case (sym, branchExp) => (sym, visitExpr(branchExp))
      }
      ReducedAst.Expr.Branch(e, bs, tpe, purity, loc)

    case LiftedAst.Expr.JumpTo(sym, tpe, purity, loc) =>
      ReducedAst.Expr.JumpTo(sym, tpe, purity, loc)

    case LiftedAst.Expr.Let(sym, exp1, exp2, _, _, loc) =>
      val e1 = visitExprInnerWithBinders(binders)(exp1)
      binders.addOne(LetBinder(sym, e1, loc))
      visitExprInnerWithBinders(binders)(exp2)

    case LiftedAst.Expr.Stm(exp1, exp2, _, _, loc) =>
      val e1 = visitExprInnerWithBinders(binders)(exp1)
      binders.addOne(NonBinder(e1, loc))
      visitExprInnerWithBinders(binders)(exp2)

    case LiftedAst.Expr.Scope(sym, exp, tpe, purity, loc) =>
      val e = visitExpr(exp)
      ReducedAst.Expr.Scope(sym, e, tpe, purity, loc)

    case LiftedAst.Expr.TryCatch(exp, rules0, tpe, purity, loc) =>
      val e = visitExpr(exp)
      val rules = rules0.map {
        case LiftedAst.CatchRule(sym, clazz, body) =>
          // assumes that catch rule is control pure
          val b = visitExpr(body)
          ReducedAst.CatchRule(sym, clazz, b)
      }
      ReducedAst.Expr.TryCatch(e, rules, tpe, purity, loc)

    case LiftedAst.Expr.RunWith(exp, effUse, rules, tpe, purity, loc) =>
      val e = visitExpr(exp)
      val rs = rules.map {
        case LiftedAst.HandlerRule(symUse, fparams0, body) =>
          val fparams = fparams0.map(visitParam)
          val b = visitExpr(body)
          ReducedAst.HandlerRule(symUse, fparams, b)
      }
      ReducedAst.Expr.RunWith(e, effUse, rs, ExpPosition.NonTail, tpe, purity, loc)

    case LiftedAst.Expr.NewObject(name, clazz, tpe, purity, methods, loc) =>
      val ms = methods.map(visitJvmMethod)
      ReducedAst.Expr.NewObject(name, clazz, tpe, purity, ms, loc)
  }

  /**
    * Transforms the [[LiftedAst.Expr]] such that effect operations will be run without an
    * operand stack. The outer-most expression IS let-bound along with all
    * sub-expressions. A variable or a constant is always returned.
    *
    * Necessary bindings are added to binders, where the first binder is the
    * outermost one.
    */
  private def visitExprWithBinders(binders: mutable.ArrayBuffer[Binder])(exp: LiftedAst.Expr)(implicit flix: Flix): ReducedAst.Expr = {
    /**
      * Let-binds the given expression, unless its a variable or constant.
      * If the given argument is a binder, then the structure is flattened.
      */
    @tailrec
    def bind(e: ReducedAst.Expr): ReducedAst.Expr = e match {
      // trivial expressions
      case ReducedAst.Expr.Cst(_, _, _) => e
      case ReducedAst.Expr.Var(_, _, _) => e
      case ReducedAst.Expr.JumpTo(_, _, _, _) => e
      case ReducedAst.Expr.ApplyAtomic(_, _, _, _, _) => e
      // non-trivial expressions
      case ReducedAst.Expr.ApplyClo(_, _, _, _, _, _) => letBindExpr(binders)(e)
      case ReducedAst.Expr.ApplyDef(_, _, _, _, _, _) => letBindExpr(binders)(e)
      case ReducedAst.Expr.ApplyOp(_, _, _, _, _) => letBindExpr(binders)(e)
      case ReducedAst.Expr.ApplySelfTail(_, _, _, _, _) => letBindExpr(binders)(e)
      case ReducedAst.Expr.IfThenElse(_, _, _, _, _, _) => letBindExpr(binders)(e)
      case ReducedAst.Expr.Branch(_, _, _, _, _) => letBindExpr(binders)(e)
      case ReducedAst.Expr.Let(sym, exp1, exp2, _, _, loc) =>
        binders.addOne(LetBinder(sym, exp1, loc))
        bind(exp2)
      case ReducedAst.Expr.Stmt(exp1, exp2, _, _, loc) =>
        binders.addOne(NonBinder(exp1, loc))
        bind(exp2)
      case ReducedAst.Expr.Scope(_, _, _, _, _) => letBindExpr(binders)(e)
      case ReducedAst.Expr.TryCatch(_, _, _, _, _) => letBindExpr(binders)(e)
      case ReducedAst.Expr.RunWith(_, _, _, _, _, _, _) => letBindExpr(binders)(e)
      case ReducedAst.Expr.NewObject(_, _, _, _, _, _) => letBindExpr(binders)(e)
    }

    bind(visitExprInnerWithBinders(binders)(exp))
  }

  /**
    * Simply let-binds the given expression, adding a [[ReducedAst.Expr.Let]] to binders.
    * The local params of [[LocalContext]] is updated with this new binder.
    */
  private def letBindExpr(binders: mutable.ArrayBuffer[Binder])(e: ReducedAst.Expr)(implicit flix: Flix): ReducedAst.Expr.Var = {
    val loc = e.loc.asSynthetic
    val sym = Symbol.freshVarSym("anf", BoundBy.Let, loc)
    binders.addOne(LetBinder(sym, e, loc))
    ReducedAst.Expr.Var(sym, e.tpe, loc)
  }

  /**
    * Returns an [[ReducedAst.Expr]] where the given binders is a chained [[ReducedAst.Expr.Let]]
    * expression. The first binder will be the outer-most one.
    */
  private def bindBinders(binders: mutable.ArrayBuffer[Binder], exp: ReducedAst.Expr): ReducedAst.Expr = {
    binders.foldRight(exp) {
      case (LetBinder(sym, exp1, loc), acc) =>
        ReducedAst.Expr.Let(sym, exp1, acc, acc.tpe, Purity.combine(acc.purity, exp1.purity), loc)
      case (NonBinder(exp1, loc), acc) =>
        ReducedAst.Expr.Stmt(exp1, acc, acc.tpe, Purity.combine(acc.purity, exp1.purity), loc)
    }
  }

}
