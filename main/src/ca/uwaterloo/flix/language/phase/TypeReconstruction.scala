/*
 * Copyright 2015-2023 Magnus Madsen, Matthew Lutze
 * Copyright 2024 Alexander Dybdahl Troelsen
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

import ca.uwaterloo.flix.language.ast.*
import ca.uwaterloo.flix.language.ast.Type.getFlixType
import ca.uwaterloo.flix.language.ast.shared.{CheckedCastType, Constant, SolveMode}
import ca.uwaterloo.flix.language.errors.TypeError
import ca.uwaterloo.flix.language.phase.typer.SubstitutionTree
import ca.uwaterloo.flix.util.collection.MapOps

import java.lang.reflect.Executable

object TypeReconstruction {

  /**
    * Reconstructs types in the given def.
    */
  def visitDef(defn: KindedAst.Def, subst: SubstitutionTree): TypedAst.Def = defn match {
    case KindedAst.Def(sym, spec0, exp0, loc) =>
      val spec = visitSpec(spec0)
      val exp = visitExp(exp0)(subst)
      TypedAst.Def(sym, spec, exp, loc)
  }

  /**
    * Reconstructs types in the given sig.
    */
  def visitSig(sig: KindedAst.Sig, subst: SubstitutionTree): TypedAst.Sig = sig match {
    case KindedAst.Sig(sym, spec0, exp0, loc) =>
      val spec = visitSpec(spec0)
      val exp = exp0.map(visitExp(_)(subst))
      TypedAst.Sig(sym, spec, exp, loc)
  }

  /**
    * Reconstructs types in the given spec.
    */
  private def visitSpec(spec: KindedAst.Spec): TypedAst.Spec = spec match {
    case KindedAst.Spec(doc, ann, mod, tparams0, fparams0, sc, tpe, eff, tconstrs, econstrs) =>
      val tparams = tparams0.map(visitTypeParam)
      val fparams = fparams0.map(visitFormalParam(_, SubstitutionTree.empty))
      // We do not perform substitution on any of the types because they should all be rigid.
      TypedAst.Spec(doc, ann, mod, tparams, fparams, sc, tpe, eff, tconstrs, econstrs)
  }

  /**
    * Reconstructs types in the given tparams.
    */
  private def visitTypeParam(tparam: KindedAst.TypeParam): TypedAst.TypeParam = tparam match {
    case KindedAst.TypeParam(name, sym, loc) => TypedAst.TypeParam(name, sym, loc)
  }

  /**
    * Reconstructs types in the given fparams.
    */
  private def visitFormalParam(fparam: KindedAst.FormalParam, subst: SubstitutionTree): TypedAst.FormalParam = fparam match {
    case KindedAst.FormalParam(sym, mod, tpe0, src, loc) =>
      val tpe = subst(tpe0)
      val bnd = TypedAst.Binder(sym, tpe)
      TypedAst.FormalParam(bnd, mod, tpe, src, loc)
  }

  /**
    * Reconstructs types in the given operation.
    */
  def visitOp(op: KindedAst.Op): TypedAst.Op = op match {
    case KindedAst.Op(sym, spec0, loc) =>
      val spec = visitSpec(spec0)
      TypedAst.Op(sym, spec, loc)
  }

  /**
    * Reconstructs types in the given expression.
    */
  private def visitExp(exp0: KindedAst.Expr)(implicit subst: SubstitutionTree): TypedAst.Expr = exp0 match {
    case KindedAst.Expr.Var(sym, loc) =>
      TypedAst.Expr.Var(sym, subst(sym.tvar), loc)

    case KindedAst.Expr.Hole(sym, env, tpe, evar, loc) =>
      TypedAst.Expr.Hole(sym, env, subst(tpe), subst(evar), loc)

    case KindedAst.Expr.HoleWithExp(exp, env, tvar, evar, loc) =>
      val e = visitExp(exp)
      TypedAst.Expr.HoleWithExp(e, env, subst(tvar), subst(evar), loc)

    case KindedAst.Expr.OpenAs(symUse, exp, tvar, loc) =>
      val e = visitExp(exp)
      TypedAst.Expr.OpenAs(symUse, e, subst(tvar), loc)

    case KindedAst.Expr.Use(sym, alias, exp, loc) =>
      val e = visitExp(exp)
      TypedAst.Expr.Use(sym, alias, e, loc)

    case KindedAst.Expr.Cst(Constant.Null, loc) =>
      TypedAst.Expr.Cst(Constant.Null, Type.Null, loc)

    case KindedAst.Expr.Cst(cst, loc) => TypedAst.Expr.Cst(cst, Type.constantType(cst), loc)

    case KindedAst.Expr.ApplyClo(exp1, exp2, tvar, evar, loc) =>
      val e1 = visitExp(exp1)
      val e2 = visitExp(exp2)
      TypedAst.Expr.ApplyClo(e1, e2, subst(tvar), subst(evar), loc)

    case KindedAst.Expr.ApplyDef(symUse, exps, targs, itvar, tvar, evar, loc) =>
      val es = exps.map(visitExp)
      val tas = targs.map(subst.apply)
      TypedAst.Expr.ApplyDef(symUse, es, tas, subst(itvar), subst(tvar), subst(evar), loc)

    case KindedAst.Expr.ApplyLocalDef(symUse, exps, arrowTvar, tvar, evar, loc) =>
      val es = exps.map(visitExp)
      val at = subst(arrowTvar)
      val t = subst(tvar)
      val ef = subst(evar)
      TypedAst.Expr.ApplyLocalDef(symUse, es, at, t, ef, loc)

    case KindedAst.Expr.ApplyOp(symUse, exps, tvar, evar, loc) =>
      val es = exps.map(visitExp(_))
      val tpe = subst(tvar)
      val eff = subst(evar)
      TypedAst.Expr.ApplyOp(symUse, es, tpe, eff, loc)

    case KindedAst.Expr.ApplySig(symUse, exps, targ, targs, itvar, tvar, evar, loc) =>
      val es = exps.map(visitExp)
      val ta = subst(targ)
      val tas = targs.map(subst.apply)
      TypedAst.Expr.ApplySig(symUse, es, ta, tas, subst(itvar), subst(tvar), subst(evar), loc)

    case KindedAst.Expr.Lambda(fparam, exp, _, loc) =>
      val p = visitFormalParam(fparam, subst)
      val e = visitExp(exp)
      val t = Type.mkArrowWithEffect(p.tpe, e.eff, e.tpe, loc)
      TypedAst.Expr.Lambda(p, e, t, loc)

    case KindedAst.Expr.Unary(sop, exp, tvar, loc) =>
      val e = visitExp(exp)
      val eff = e.eff
      TypedAst.Expr.Unary(sop, e, subst(tvar), eff, loc)

    case KindedAst.Expr.Binary(sop, exp1, exp2, tvar, loc) =>
      val e1 = visitExp(exp1)
      val e2 = visitExp(exp2)
      val eff = Type.mkUnion(e1.eff, e2.eff, loc)
      TypedAst.Expr.Binary(sop, e1, e2, subst(tvar), eff, loc)

    case KindedAst.Expr.IfThenElse(exp1, exp2, exp3, loc) =>
      val e1 = visitExp(exp1)
      val e2 = visitExp(exp2)
      val e3 = visitExp(exp3)
      val tpe = e2.tpe
      val eff = Type.mkUnion(e1.eff, e2.eff, e3.eff, loc)
      TypedAst.Expr.IfThenElse(e1, e2, e3, tpe, eff, loc)

    case KindedAst.Expr.Stm(exp1, exp2, loc) =>
      val e1 = visitExp(exp1)
      val e2 = visitExp(exp2)
      val tpe = e2.tpe
      val eff = Type.mkUnion(e1.eff, e2.eff, loc)
      TypedAst.Expr.Stm(e1, e2, tpe, eff, loc)

    case KindedAst.Expr.Discard(exp, loc) =>
      val e = visitExp(exp)
      TypedAst.Expr.Discard(e, e.eff, loc)

    case KindedAst.Expr.Let(sym, exp1, exp2, loc) =>
      val e1 = visitExp(exp1)
      val e2 = visitExp(exp2)
      val bnd = TypedAst.Binder(sym, e1.tpe)
      val tpe = e2.tpe
      val eff = Type.mkUnion(e1.eff, e2.eff, loc)
      TypedAst.Expr.Let(bnd, e1, e2, tpe, eff, loc)

    case KindedAst.Expr.LocalDef(sym, fparams, exp1, exp2, loc) =>
      val fps = fparams.map(visitFormalParam(_, subst))
      val e1 = visitExp(exp1)
      val e2 = visitExp(exp2)
      val tpe = e2.tpe
      val eff = e2.eff
      val boundType = Type.mkUncurriedArrowWithEffect(fps.map(_.tpe), e1.tpe, e1.eff, SourceLocation.Unknown)
      val bnd = TypedAst.Binder(sym, boundType)
      TypedAst.Expr.LocalDef(bnd, fps, e1, e2, tpe, eff, loc)

    case KindedAst.Expr.Region(tpe, loc) =>
      TypedAst.Expr.Region(tpe, loc)

    case KindedAst.Expr.Scope(sym, regSym, exp, tvar, evar, loc) =>
      // Use the appropriate branch for the scope.
      val e = visitExp(exp)(subst.branches.getOrElse(regSym, SubstitutionTree.empty))
      val tpe = subst(tvar)
      val eff = subst(evar)
      val bnd = TypedAst.Binder(sym, eff)
      TypedAst.Expr.Scope(bnd, regSym, e, tpe, eff, loc)

    case KindedAst.Expr.Match(matchExp, rules, loc) =>
      val e1 = visitExp(matchExp)
      val rs = rules map {
        case KindedAst.MatchRule(pat, guard, exp, ruleLoc) =>
          val p = visitPattern(pat)
          val g = guard.map(visitExp(_))
          val b = visitExp(exp)
          TypedAst.MatchRule(p, g, b, ruleLoc)
      }
      val tpe = rs.head.exp.tpe
      val eff = rs.foldLeft(e1.eff) {
        case (acc, TypedAst.MatchRule(_, g, b, _)) => Type.mkUnion(g.map(_.eff).toList ::: List(b.eff, acc), loc)
      }
      TypedAst.Expr.Match(e1, rs, tpe, eff, loc)

    case KindedAst.Expr.TypeMatch(matchExp, rules, loc) =>
      val e1 = visitExp(matchExp)
      val rs = rules map {
        case KindedAst.TypeMatchRule(sym, tpe0, exp, ruleLoc) =>
          val t = subst(tpe0)
          val b = visitExp(exp)
          val bnd = TypedAst.Binder(sym, t)
          TypedAst.TypeMatchRule(bnd, t, b, ruleLoc)
      }
      val tpe = rs.head.exp.tpe
      val eff = rs.foldLeft(e1.eff) {
        case (acc, TypedAst.TypeMatchRule(_, _, b, _)) => Type.mkUnion(b.eff, acc, loc)
      }
      TypedAst.Expr.TypeMatch(e1, rs, tpe, eff, loc)

    case KindedAst.Expr.RestrictableChoose(star, exp, rules, tvar, loc) =>
      val e = visitExp(exp)
      val rs = rules.map {
        case KindedAst.RestrictableChooseRule(pat0, body0) =>
          val pat = pat0 match {
            case KindedAst.RestrictableChoosePattern.Tag(symUse, pats, tagTvar, tagLoc) =>
              val ps = pats.map {
                case KindedAst.RestrictableChoosePattern.Wild(wildTvar, wildLoc) => TypedAst.RestrictableChoosePattern.Wild(subst(wildTvar), wildLoc)
                case KindedAst.RestrictableChoosePattern.Var(sym, varTvar, varLoc) => TypedAst.RestrictableChoosePattern.Var(TypedAst.Binder(sym, subst(varTvar)), subst(varTvar), varLoc)
                case KindedAst.RestrictableChoosePattern.Error(errTvar, errLoc) => TypedAst.RestrictableChoosePattern.Error(subst(errTvar), errLoc)
              }
              TypedAst.RestrictableChoosePattern.Tag(symUse, ps, subst(tagTvar), tagLoc)
            case KindedAst.RestrictableChoosePattern.Error(errTvar, errLoc) => TypedAst.RestrictableChoosePattern.Error(subst(errTvar), errLoc)
          }
          val body = visitExp(body0)
          TypedAst.RestrictableChooseRule(pat, body)
      }
      val eff = Type.mkUnion(rs.map(_.exp.eff), loc)
      TypedAst.Expr.RestrictableChoose(star, e, rs, subst(tvar), eff, loc)

    case KindedAst.Expr.ExtMatch(exp, rules, loc) =>
      val e = visitExp(exp)
      val rs = rules.map(visitExtMatchRule)
      val tpe = rs.head.exp.tpe // Note: We are guaranteed to have at least one rule.
      val eff = Type.mkUnion(e.eff :: rs.map(_.exp.eff), loc)
      TypedAst.Expr.ExtMatch(e, rs, tpe, eff, loc)

    case KindedAst.Expr.Tag(symUse, exps, tvar, loc) =>
      val es = exps.map(visitExp)
      val eff = Type.mkUnion(es.map(_.eff), loc)
      TypedAst.Expr.Tag(symUse, es, subst(tvar), eff, loc)

    case KindedAst.Expr.RestrictableTag(symUse, exps, _, tvar, evar, loc) =>
      val es = exps.map(visitExp)
      TypedAst.Expr.RestrictableTag(symUse, es, subst(tvar), subst(evar), loc)

    case KindedAst.Expr.ExtTag(label, exps, tvar, loc) =>
      val es = exps.map(visitExp)
      val tpe = subst(tvar)
      val eff = Type.mkUnion(es.map(_.eff), loc)
      TypedAst.Expr.ExtTag(label, es, tpe, eff, loc)

    case KindedAst.Expr.Tuple(elms, loc) =>
      val es = elms.map(visitExp(_))
      val tpe = Type.mkTuple(es.map(_.tpe), loc)
      val eff = Type.mkUnion(es.map(_.eff), loc)
      TypedAst.Expr.Tuple(es, tpe, eff, loc)

    case KindedAst.Expr.RecordSelect(exp, field, tvar, loc) =>
      val e = visitExp(exp)
      val eff = e.eff
      TypedAst.Expr.RecordSelect(e, field, subst(tvar), eff, loc)

    case KindedAst.Expr.RecordExtend(field, value, rest, tvar, loc) =>
      val v = visitExp(value)
      val r = visitExp(rest)
      val eff = Type.mkUnion(v.eff, r.eff, loc)
      TypedAst.Expr.RecordExtend(field, v, r, subst(tvar), eff, loc)

    case KindedAst.Expr.RecordRestrict(field, rest, tvar, loc) =>
      val r = visitExp(rest)
      val eff = r.eff
      TypedAst.Expr.RecordRestrict(field, r, subst(tvar), eff, loc)

    case KindedAst.Expr.ArrayLit(exps, exp, tvar, evar, loc) =>
      val es = exps.map(visitExp(_))
      val e = visitExp(exp)
      val tpe = subst(tvar)
      val eff = subst(evar)
      TypedAst.Expr.ArrayLit(es, e, tpe, eff, loc)

    case KindedAst.Expr.ArrayNew(exp1, exp2, exp3, tvar, evar, loc) =>
      val e1 = visitExp(exp1)
      val e2 = visitExp(exp2)
      val e3 = visitExp(exp3)
      val tpe = subst(tvar)
      val eff = subst(evar)
      TypedAst.Expr.ArrayNew(e1, e2, e3, tpe, eff, loc)

    case KindedAst.Expr.ArrayLoad(exp1, exp2, tvar, evar, loc) =>
      val e1 = visitExp(exp1)
      val e2 = visitExp(exp2)
      val tpe = subst(tvar)
      val eff = subst(evar)
      TypedAst.Expr.ArrayLoad(e1, e2, tpe, eff, loc)

    case KindedAst.Expr.ArrayStore(exp1, exp2, exp3, evar, loc) =>
      val e1 = visitExp(exp1)
      val e2 = visitExp(exp2)
      val e3 = visitExp(exp3)
      val eff = subst(evar)
      TypedAst.Expr.ArrayStore(e1, e2, e3, eff, loc)

    case KindedAst.Expr.ArrayLength(exp, evar, loc) =>
      val e = visitExp(exp)
      val eff = subst(evar)
      TypedAst.Expr.ArrayLength(e, eff, loc)

    case KindedAst.Expr.StructNew(sym, fields0, region0, tvar, evar, loc) =>
      val region = visitExp(region0)
      val fields = fields0.map { case (k, v) => (k, visitExp(v)) }
      val tpe = subst(tvar)
      val eff = subst(evar)
      TypedAst.Expr.StructNew(sym, fields, region, tpe, eff, loc)

    case KindedAst.Expr.StructGet(e0, symUse, tvar, evar, loc) =>
      val e = visitExp(e0)
      val tpe = subst(tvar)
      val eff = subst(evar)
      TypedAst.Expr.StructGet(e, symUse, tpe, eff, loc)

    case KindedAst.Expr.StructPut(exp1, symUse, exp2, tvar, evar, loc) =>
      val e1 = visitExp(exp1)
      val e2 = visitExp(exp2)
      val tpe = subst(tvar)
      val eff = subst(evar)
      TypedAst.Expr.StructPut(e1, symUse, e2, tpe, eff, loc)

    case KindedAst.Expr.VectorLit(exps, tvar, evar, loc) =>
      val es = exps.map(visitExp(_))
      val tpe = subst(tvar)
      val eff = subst(evar)
      TypedAst.Expr.VectorLit(es, tpe, eff, loc)

    case KindedAst.Expr.VectorLoad(exp1, exp2, tvar, evar, loc) =>
      val e1 = visitExp(exp1)
      val e2 = visitExp(exp2)
      val tpe = subst(tvar)
      val eff = subst(evar)
      TypedAst.Expr.VectorLoad(e1, e2, tpe, eff, loc)

    case KindedAst.Expr.VectorLength(exp, loc) =>
      val e = visitExp(exp)
      TypedAst.Expr.VectorLength(e, loc)

    case KindedAst.Expr.Ascribe(exp, expectedType, expectedEff, tvar, loc) =>
      val e = visitExp(exp)
      val eff = e.eff
      TypedAst.Expr.Ascribe(e, expectedType, expectedEff, subst(tvar), eff, loc)

    case KindedAst.Expr.InstanceOf(exp, clazz, loc) =>
      val e1 = visitExp(exp)
      TypedAst.Expr.InstanceOf(e1, clazz, loc)

    case KindedAst.Expr.CheckedCast(cast, exp, tvar, evar, loc) =>
      cast match {
        case CheckedCastType.TypeCast =>
          val e = visitExp(exp)
          val tpe = subst(tvar)
          TypedAst.Expr.CheckedCast(cast, e, tpe, e.eff, loc)
        case CheckedCastType.EffectCast =>
          val e = visitExp(exp)
          val eff = Type.mkUnion(e.eff, subst(evar), loc)
          TypedAst.Expr.CheckedCast(cast, e, e.tpe, eff, loc)
      }

    case KindedAst.Expr.UncheckedCast(exp, declaredType0, declaredEff0, tvar, loc) =>
      val e = visitExp(exp)
      val declaredType = declaredType0.map(tpe => subst(tpe))
      val declaredEff = declaredEff0.map(eff => subst(eff))
      val tpe = subst(tvar)
      val eff = declaredEff0.getOrElse(e.eff)
      TypedAst.Expr.UncheckedCast(e, declaredType, declaredEff, tpe, eff, loc)

    case KindedAst.Expr.Unsafe(exp, eff0, loc) =>
      val e = visitExp(exp)
      val eff = Type.mkDifference(e.eff, eff0, loc)
      TypedAst.Expr.Unsafe(e, eff0, e.tpe, eff, loc)

    case KindedAst.Expr.Without(exp, symUse, loc) =>
      val e = visitExp(exp)
      val tpe = e.tpe
      val eff = e.eff
      TypedAst.Expr.Without(e, symUse, tpe, eff, loc)

    case KindedAst.Expr.TryCatch(exp, rules, loc) =>
      val e = visitExp(exp)
      val rs = rules map {
        case KindedAst.CatchRule(sym, clazz, body, ruleLoc) =>
          val b = visitExp(body)
          val bnd = TypedAst.Binder(sym, Type.mkNative(clazz, SourceLocation.Unknown))
          TypedAst.CatchRule(bnd, clazz, b, ruleLoc)
      }
      val tpe = rs.head.exp.tpe
      val eff = Type.mkUnion(e.eff :: rs.map(_.exp.eff), loc)
      TypedAst.Expr.TryCatch(e, rs, tpe, eff, loc)

    case KindedAst.Expr.Throw(exp, tvar, evar, loc) =>
      val e = visitExp(exp)
      val tpe = subst(tvar)
      val eff = subst(evar)
      TypedAst.Expr.Throw(e, tpe, eff, loc)

    case KindedAst.Expr.Handler(effectSymUse, rules, tvar, evar1, evar2, loc) =>
      val rs = rules map {
        case KindedAst.HandlerRule(opSymUse, fparams, hexp, _, ruleLoc) =>
          val fps = fparams.map(visitFormalParam(_, subst))
          val he = visitExp(hexp)
          TypedAst.HandlerRule(opSymUse, fps, he, ruleLoc)
      }
      val bodyTpe = subst(tvar)
      val bodyEff = subst(evar1)
      val handledEff = subst(evar2)
      val tpe = Type.mkArrowWithEffect(Type.mkArrowWithEffect(Type.Unit, bodyEff, bodyTpe, loc.asSynthetic), handledEff, bodyTpe, loc.asSynthetic)
      TypedAst.Expr.Handler(effectSymUse, rs, bodyTpe, bodyEff, handledEff, tpe, loc)

    case KindedAst.Expr.RunWith(exp1, exp2, tvar, evar, loc) =>
      val e1 = visitExp(exp1)
      val e2 = visitExp(exp2)
      TypedAst.Expr.RunWith(e1, e2, subst(tvar), Type.mkUnion(subst(evar), e2.eff, loc.asSynthetic), loc)

    case KindedAst.Expr.InvokeConstructor(clazz, exps, jvar, evar, loc) =>
      val es0 = exps.map(visitExp)
      val constructorTpe = subst(jvar)
      val tpe = Type.getFlixType(clazz)
      val eff = subst(evar)
      constructorTpe match {
        case Type.Cst(TypeConstructor.JvmConstructor(constructor), _) =>
          val es = getArgumentsWithVarArgs(constructor, es0, loc)
          TypedAst.Expr.InvokeConstructor(constructor, es, tpe, eff, loc)
        case _ =>
          TypedAst.Expr.Error(TypeError.UnresolvedConstructor(loc), tpe, eff)
      }

    case KindedAst.Expr.InvokeMethod(exp, _, exps, jvar, tvar, evar, loc) =>
      val e = visitExp(exp)
      val es0 = exps.map(visitExp)
      val returnTpe = subst(tvar)
      val methodTpe = subst(jvar)
      val eff = subst(evar)
      methodTpe match {
        case Type.Cst(TypeConstructor.JvmMethod(method), methLoc) =>
          val es = getArgumentsWithVarArgs(method, es0, methLoc)
          TypedAst.Expr.InvokeMethod(method, e, es, returnTpe, eff, methLoc)
        case _ =>
          TypedAst.Expr.Error(TypeError.UnresolvedMethod(loc), methodTpe, eff)
      }

    case KindedAst.Expr.InvokeStaticMethod(_, _, exps, jvar, tvar, evar, loc) =>
      val es0 = exps.map(visitExp)
      val methodTpe = subst(jvar)
      val returnTpe = subst(tvar)
      val eff = subst(evar)
      methodTpe match {
        case Type.Cst(TypeConstructor.JvmMethod(method), methLoc) =>
          val es = getArgumentsWithVarArgs(method, es0, methLoc)
          TypedAst.Expr.InvokeStaticMethod(method, es, returnTpe, eff, methLoc)
        case _ =>
          TypedAst.Expr.Error(TypeError.UnresolvedStaticMethod(loc), methodTpe, eff)
      }

    case KindedAst.Expr.GetField(exp, _, jvar, tvar, evar, loc) =>
      val e = visitExp(exp)
      val fieldType = subst(tvar)
      val jvarType = subst(jvar)
      val eff = subst(evar)
      jvarType match {
        case Type.Cst(TypeConstructor.JvmField(field), fieldLoc) =>
          TypedAst.Expr.GetField(field, e, fieldType, eff, fieldLoc)
        case _ =>
          TypedAst.Expr.Error(TypeError.UnresolvedField(loc), jvarType, eff)
      }

    case KindedAst.Expr.PutField(field, _, exp1, exp2, loc) =>
      val e1 = visitExp(exp1)
      val e2 = visitExp(exp2)
      val tpe = Type.Unit
      val eff = Type.mkUnion(e1.eff, e2.eff, Type.IO, loc)
      TypedAst.Expr.PutField(field, e1, e2, tpe, eff, loc)

    case KindedAst.Expr.GetStaticField(field, loc) =>
      val tpe = getFlixType(field.getType)
      val eff = Type.IO
      TypedAst.Expr.GetStaticField(field, tpe, eff, loc)

    case KindedAst.Expr.PutStaticField(field, exp, loc) =>
      val e = visitExp(exp)
      val tpe = Type.Unit
      val eff = Type.mkUnion(e.eff, Type.IO, loc)
      TypedAst.Expr.PutStaticField(field, e, tpe, eff, loc)

    case KindedAst.Expr.NewObject(name, clazz, methods, loc) =>
      val tpe = getFlixType(clazz)
      val eff = Type.IO
      val ms = methods map visitJvmMethod
      TypedAst.Expr.NewObject(name, clazz, tpe, eff, ms, loc)

    case KindedAst.Expr.NewChannel(exp, tvar, loc) =>
      val e = visitExp(exp)
      val eff = Type.mkUnion(e.eff, Type.Chan, loc)
      TypedAst.Expr.NewChannel(e, subst(tvar), eff, loc)

    case KindedAst.Expr.GetChannel(exp, tvar, evar, loc) =>
      val e = visitExp(exp)
      TypedAst.Expr.GetChannel(e, subst(tvar), subst(evar), loc)

    case KindedAst.Expr.PutChannel(exp1, exp2, evar, loc) =>
      val e1 = visitExp(exp1)
      val e2 = visitExp(exp2)
      val tpe = Type.mkUnit(loc)
      TypedAst.Expr.PutChannel(e1, e2, tpe, subst(evar), loc)

    case KindedAst.Expr.SelectChannel(rules, default, tvar, evar, loc) =>
      val rs = rules map {
        case KindedAst.SelectChannelRule(sym, chan, exp, ruleLoc) =>
          val c = visitExp(chan)
          val b = visitExp(exp)
          val bnd = TypedAst.Binder(sym, c.tpe)
          TypedAst.SelectChannelRule(bnd, c, b, ruleLoc)
      }
      val d = default.map(visitExp(_))
      TypedAst.Expr.SelectChannel(rs, d, subst(tvar), subst(evar), loc)

    case KindedAst.Expr.Spawn(exp1, exp2, loc) =>
      val e1 = visitExp(exp1)
      val e2 = visitExp(exp2)
      val tpe = Type.Unit
      val eff = Type.mkUnion(e1.eff, e2.eff, loc)
      TypedAst.Expr.Spawn(e1, e2, tpe, eff, loc)

    case KindedAst.Expr.ParYield(frags, exp, loc) =>
      val e = visitExp(exp)
      val fs = frags map {
        case KindedAst.ParYieldFragment(pat, e0, l0) =>
          val p = visitPattern(pat)
          val e1 = visitExp(e0)
          TypedAst.ParYieldFragment(p, e1, l0)
      }
      val tpe = e.tpe
      val eff = fs.foldLeft(e.eff) {
        case (acc, TypedAst.ParYieldFragment(_, e1, _)) => Type.mkUnion(acc, e1.eff, loc)
      }
      TypedAst.Expr.ParYield(fs, e, tpe, eff, loc)

    case KindedAst.Expr.Lazy(exp, loc) =>
      val e = visitExp(exp)
      val tpe = Type.mkLazy(e.tpe, loc)
      TypedAst.Expr.Lazy(e, tpe, loc)

    case KindedAst.Expr.Force(exp, tvar, loc) =>
      val e = visitExp(exp)
      val tpe = subst(tvar)
      val eff = e.eff
      TypedAst.Expr.Force(e, tpe, eff, loc)

    case KindedAst.Expr.FixpointConstraintSet(cs0, tvar, loc) =>
      val cs = cs0.map(visitConstraint)
      TypedAst.Expr.FixpointConstraintSet(cs, subst(tvar), loc)

    case KindedAst.Expr.FixpointLambda(pparams, exp, tvar, loc) =>
      val ps = pparams.map(visitPredicateParam)
      val e = visitExp(exp)
      val tpe = subst(tvar)
      val eff = e.eff
      TypedAst.Expr.FixpointLambda(ps, e, tpe, eff, loc)

    case KindedAst.Expr.FixpointMerge(exp1, exp2, loc) =>
      val e1 = visitExp(exp1)
      val e2 = visitExp(exp2)
      val tpe = e1.tpe
      val eff = Type.mkUnion(e1.eff, e2.eff, loc)
      TypedAst.Expr.FixpointMerge(e1, e2, tpe, eff, loc)

    case KindedAst.Expr.FixpointQueryWithProvenance(exps, select, withh, tvar, loc) =>
      val es = exps.map(visitExp)
      val s = visitHeadPredicate(select)
      val eff = Type.Pure
      TypedAst.Expr.FixpointQueryWithProvenance(es, s, withh, subst(tvar), eff, loc)

    case KindedAst.Expr.FixpointSolve(exp, mode, loc) =>
      val e = visitExp(exp)
      val tpe = e.tpe
      val eff = e.eff
      TypedAst.Expr.FixpointSolve(e, tpe, eff, mode, loc)

    case KindedAst.Expr.FixpointFilter(pred, exp, tvar, loc) =>
      val e = visitExp(exp)
      val eff = e.eff
      TypedAst.Expr.FixpointFilter(pred, e, subst(tvar), eff, loc)

    case KindedAst.Expr.FixpointInject(exp, pred, _, tvar, evar, loc) =>
      val e = visitExp(exp)
      TypedAst.Expr.FixpointInject(e, pred, subst(tvar), subst(evar), loc)

    case KindedAst.Expr.FixpointProject(pred, _, exp1, exp2, tvar, loc) =>
      val e1 = visitExp(exp1)
      val e2 = visitExp(exp2)
      val tpe = subst(tvar)
      val eff = Type.mkUnion(e1.eff, e2.eff, loc)

      // Note: This transformation should happen in the Weeder but it is here because
      // `#{#Result(..)` | _} cannot be unified with `#{A(..)}` (a closed row).
      // See Weeder for more details.
      val mergeExp = TypedAst.Expr.FixpointMerge(e1, e2, e1.tpe, eff, loc)
      val solveExp = TypedAst.Expr.FixpointSolve(mergeExp, e1.tpe, eff, SolveMode.Default, loc)
      TypedAst.Expr.FixpointProject(pred, solveExp, tpe, eff, loc)

    case KindedAst.Expr.Error(m, tvar, evar) =>
      val tpe = subst(tvar)
      val eff = subst(evar)
      TypedAst.Expr.Error(m, tpe, eff)
  }

  /**
    * Returns the given arguments `es` possibly with an empty VarArgs array added as the last argument.
    */
  private def getArgumentsWithVarArgs(exc: Executable, es: List[TypedAst.Expr], loc: SourceLocation): List[TypedAst.Expr] = {
    val declaredArity = exc.getParameterCount
    val actualArity = es.length
    // Check if (a) an argument is missing and (b) the constructor/method is VarArgs.
    if (actualArity == declaredArity - 1 && exc.isVarArgs) {
      // Case 1: Argument missing. Introduce a new empty vector argument.
      val varArgsType = Type.mkNative(exc.getParameterTypes.last.getComponentType, loc)
      val varArgs = TypedAst.Expr.VectorLit(Nil, Type.mkVector(varArgsType, loc), Type.Pure, loc)
      es ::: varArgs :: Nil
    } else {
      // Case 2: No argument missing. Return the arguments as-is.
      es
    }
  }

  /**
    * Applies the substitution to the given constraint.
    */
  private def visitConstraint(c0: KindedAst.Constraint)(implicit subst: SubstitutionTree): TypedAst.Constraint = {
    val KindedAst.Constraint(cparams0, head0, body0, loc) = c0

    val head = visitHeadPredicate(head0)
    val body = body0.map(b => visitBodyPredicate(b))

    val cparams = cparams0.map {
      case KindedAst.ConstraintParam(sym, l) =>
        val tpe = subst(sym.tvar)
        val bnd = TypedAst.Binder(sym, tpe)
        TypedAst.ConstraintParam(bnd, tpe, l)
    }

    TypedAst.Constraint(cparams, head, body, loc)
  }

  /**
    * Reconstructs types in the given predicate param.
    */
  private def visitPredicateParam(pparam: KindedAst.PredicateParam)(implicit subst: SubstitutionTree): TypedAst.PredicateParam =
    TypedAst.PredicateParam(pparam.pred, subst(pparam.tpe), pparam.loc)

  /**
    * Reconstructs types in the given JVM method.
    */
  private def visitJvmMethod(method: KindedAst.JvmMethod)(implicit subst: SubstitutionTree): TypedAst.JvmMethod = {
    method match {
      case KindedAst.JvmMethod(ident, fparams0, exp0, tpe, eff, loc) =>
        val fparams = fparams0.map(visitFormalParam(_, subst))
        val exp = visitExp(exp0)
        TypedAst.JvmMethod(ident, fparams, exp, tpe, eff, loc)
    }
  }

  /**
    * Reconstructs types in the given ext-match rule.
    */
  private def visitExtMatchRule(rule: KindedAst.ExtMatchRule)(implicit subst: SubstitutionTree): TypedAst.ExtMatchRule = rule match {
    case KindedAst.ExtMatchRule(label, pats, exp, loc) =>
      val ps = pats.map(visitExtPat)
      val e = visitExp(exp)
      TypedAst.ExtMatchRule(label, ps, e, loc)
  }

  /**
    * Reconstructs types in the given pattern.
    */
  private def visitPattern(pat0: KindedAst.Pattern)(implicit subst: SubstitutionTree): TypedAst.Pattern = pat0 match {
    case KindedAst.Pattern.Wild(tvar, loc) => TypedAst.Pattern.Wild(subst(tvar), loc)
    case KindedAst.Pattern.Var(sym, tvar, loc) => TypedAst.Pattern.Var(TypedAst.Binder(sym, subst(tvar)), subst(tvar), loc)
    case KindedAst.Pattern.Cst(cst, loc) => TypedAst.Pattern.Cst(cst, Type.constantType(cst), loc)

    case KindedAst.Pattern.Tag(symUse, pats, tvar, loc) => TypedAst.Pattern.Tag(symUse, pats.map(visitPattern), subst(tvar), loc)

    case KindedAst.Pattern.Tuple(elms, loc) =>
      val es = elms.map(visitPattern)
      val tpe = Type.mkTuple(es.map(_.tpe), loc)
      TypedAst.Pattern.Tuple(es, tpe, loc)

    case KindedAst.Pattern.Record(pats, pat, tvar, loc) =>
      val ps = pats.map {
        case KindedAst.Pattern.Record.RecordLabelPattern(field, pat1, tvar1, loc1) =>
          TypedAst.Pattern.Record.RecordLabelPattern(field, visitPattern(pat1), subst(tvar1), loc1)
      }
      val p = visitPattern(pat)
      TypedAst.Pattern.Record(ps, p, subst(tvar), loc)

    case KindedAst.Pattern.Error(tvar, loc) =>
      TypedAst.Pattern.Error(subst(tvar), loc)
  }

  /**
    * Reconstructs types in the given ext-pattern.
    */
  private def visitExtPat(pat0: KindedAst.ExtPattern)(implicit subst: SubstitutionTree): TypedAst.ExtPattern = pat0 match {
    case KindedAst.ExtPattern.Wild(tvar, loc) => TypedAst.ExtPattern.Wild(subst(tvar), loc)

    case KindedAst.ExtPattern.Var(sym, tvar, loc) =>
      val tpe = subst(tvar)
      val bnd = TypedAst.Binder(sym, tpe)
      TypedAst.ExtPattern.Var(bnd, tpe, loc)

    case KindedAst.ExtPattern.Error(tvar, loc) => TypedAst.ExtPattern.Error(subst(tvar), loc)
  }

  /**
    * Reconstructs types in the given head predicate.
    */
  private def visitHeadPredicate(head0: KindedAst.Predicate.Head)(implicit subst: SubstitutionTree): TypedAst.Predicate.Head = head0 match {
    case KindedAst.Predicate.Head.Atom(pred, den0, terms, tvar, loc) =>
      val ts = terms.map(t => visitExp(t))
      TypedAst.Predicate.Head.Atom(pred, den0, ts, subst(tvar), loc)
  }


  /**
    * Reconstructs types in the given body predicate.
    */
  private def visitBodyPredicate(body0: KindedAst.Predicate.Body)(implicit subst: SubstitutionTree): TypedAst.Predicate.Body = body0 match {
    case KindedAst.Predicate.Body.Atom(pred, den0, polarity, fixity, terms, tvar, loc) =>
      val ts = terms.map(t => visitPattern(t))
      TypedAst.Predicate.Body.Atom(pred, den0, polarity, fixity, ts, subst(tvar), loc)

    case KindedAst.Predicate.Body.Functional(syms, exp, loc) =>
      val e = visitExp(exp)
      val outBnds = syms.map(varSym => TypedAst.Binder(varSym, subst(varSym.tvar)))
      TypedAst.Predicate.Body.Functional(outBnds, e, loc)

    case KindedAst.Predicate.Body.Guard(exp, loc) =>
      val e = visitExp(exp)
      TypedAst.Predicate.Body.Guard(e, loc)

  }
}
