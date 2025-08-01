/*
 * Copyright 2021 Matthew Lutze
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
import ca.uwaterloo.flix.language.ast.shared.*
import ca.uwaterloo.flix.language.ast.shared.SymUse.*
import ca.uwaterloo.flix.language.ast.{Kind, KindedAst, Name, Scheme, SemanticOp, SourceLocation, Symbol, Type, TypeConstructor}
import ca.uwaterloo.flix.language.dbg.AstPrinter.DebugKindedAst
import ca.uwaterloo.flix.language.errors.DerivationError
import ca.uwaterloo.flix.language.phase.util.PredefinedTraits
import ca.uwaterloo.flix.util.ParOps
import ca.uwaterloo.flix.util.collection.Nel

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
  * Constructs instances derived from enums.
  *
  * No errors are thrown in this phase: constructed instances must be well-formed.
  * Errors with overlapping instances or unfulfilled type constraints must be caught in later phases.
  */
object Deriver {

  // We don't use regions, so we are safe to use the global scope everywhere in this phase.
  private implicit val S: Scope = Scope.Top

  private val EqSym = new Symbol.TraitSym(Nil, "Eq", SourceLocation.Unknown)
  private val OrderSym = new Symbol.TraitSym(Nil, "Order", SourceLocation.Unknown)
  private val ToStringSym = new Symbol.TraitSym(Nil, "ToString", SourceLocation.Unknown)
  private val HashSym = new Symbol.TraitSym(Nil, "Hash", SourceLocation.Unknown)
  private val CoerceSym = new Symbol.TraitSym(Nil, "Coerce", SourceLocation.Unknown)

  private val DerivableSyms: List[Symbol.TraitSym] = List(EqSym, OrderSym, ToStringSym, HashSym, CoerceSym)

  def run(root: KindedAst.Root)(implicit flix: Flix): (KindedAst.Root, List[DerivationError]) = flix.phaseNew("Deriver") {
    implicit val sctx: SharedContext = SharedContext.mk()
    val derivedInstances = ParOps.parMap(root.enums.values)(getDerivedInstances(_, root)).flatten
    val newInstances = derivedInstances.foldLeft(root.instances) {
      case (acc, inst) =>
        acc + (inst.symUse.sym -> inst)
    }
    (root.copy(instances = newInstances), sctx.errors.asScala.toList)
  }

  /**
    * Builds the instances derived from this enum.
    */
  private def getDerivedInstances(enum0: KindedAst.Enum, root: KindedAst.Root)(implicit sctx: SharedContext, flix: Flix): List[KindedAst.Instance] = enum0 match {
    case KindedAst.Enum(_, _, _, enumSym, _, derives, cases, _, _) =>

      derives.traits.flatMap {
        case Derivation(traitSym, loc) if cases.isEmpty =>
          val error = DerivationError.IllegalDerivationForEmptyEnum(enumSym, traitSym, loc)
          sctx.errors.add(error)
          None

        case Derivation(sym, loc) if sym == EqSym =>
          Some(mkEqInstance(enum0, loc.asSynthetic, root))

        case Derivation(sym, loc) if sym == OrderSym =>
          Some(mkOrderInstance(enum0, loc.asSynthetic, root))

        case Derivation(sym, loc) if sym == ToStringSym =>
          Some(mkToStringInstance(enum0, loc.asSynthetic, root))

        case Derivation(sym, loc) if sym == HashSym =>
          Some(mkHashInstance(enum0, loc.asSynthetic, root))

        case Derivation(sym, loc) if sym == CoerceSym =>
          mkCoerceInstance(enum0, loc.asSynthetic, root)

        case Derivation(sym, loc) =>
          val error = DerivationError.IllegalDerivation(sym, DerivableSyms, loc)
          sctx.errors.add(error)
          None
      }
  }

  /**
    * Creates an Eq instance for the given enum.
    *
    * {{{
    * enum E[a] with Eq {
    *   case C1
    *   case C2(a)
    *   case C3(a, a)
    * }
    * }}}
    *
    * yields
    *
    * {{{
    * instance Eq[E[a]] with Eq[a] {
    *   pub def eq(x: E[a], y: E[a]): Bool = (x, y) match {
    *     case (C0, C0) => true
    *     case (C1(x0), C1(y0)) => x0 == y0
    *     case (C2(x0, x1), C2(y0, y1)) => x0 == y0 and x1 == y1
    *     case _ => false
    *   }
    * }
    * }}}
    */
  private def mkEqInstance(enum0: KindedAst.Enum, loc: SourceLocation, root: KindedAst.Root)(implicit flix: Flix): KindedAst.Instance = enum0 match {
    case KindedAst.Enum(_, _, _, _, tparams, _, _, tpe, _) =>
      assert(loc.isSynthetic)

      val eqTraitSym = PredefinedTraits.lookupTraitSym("Eq", root)
      val eqDefSym = Symbol.mkDefnSym("Eq.eq", Some(flix.genSym.freshId()))

      val param1 = Symbol.freshVarSym("x", BoundBy.FormalParam, loc)
      val param2 = Symbol.freshVarSym("y", BoundBy.FormalParam, loc)
      val exp = mkEqImpl(enum0, param1, param2, loc, root)
      val spec = mkEqSpec(enum0, param1, param2, loc, root)

      val defn = KindedAst.Def(eqDefSym, spec, exp, loc)

      val tconstrs = getTraitConstraintsForTypeParams(tparams, eqTraitSym, loc)

      KindedAst.Instance(
        doc = Doc(Nil, loc),
        ann = Annotations.Empty,
        mod = Modifiers.Empty,
        symUse = TraitSymUse(eqTraitSym, loc),
        tparams = tparams,
        tpe = tpe,
        tconstrs = tconstrs,
        econstrs = Nil,
        assocs = Nil,
        defs = List(defn),
        ns = Name.RootNS,
        loc = loc
      )
  }

  /**
    * Creates the eq implementation for the given enum, where `param1` and `param2` are the parameters to the function.
    */
  private def mkEqImpl(enum0: KindedAst.Enum, param1: Symbol.VarSym, param2: Symbol.VarSym, loc: SourceLocation, root: KindedAst.Root)(implicit flix: Flix): KindedAst.Expr = enum0 match {
    case KindedAst.Enum(_, _, _, _, _, _, cases, _, _) =>
      // create a match rule for each case
      val mainMatchRules = getCasesInStableOrder(cases).map(mkEqMatchRule(_, loc, root))

      // create a default rule
      // `case _ => false`
      val defaultRule = KindedAst.MatchRule(KindedAst.Pattern.Wild(Type.freshVar(Kind.Star, loc), loc), None, KindedAst.Expr.Cst(Constant.Bool(false), loc), loc)

      // group the match rules in an expression
      KindedAst.Expr.Match(
        KindedAst.Expr.Tuple(List(mkVarExpr(param1, loc), mkVarExpr(param2, loc)), loc),
        mainMatchRules ++ List(defaultRule),
        loc
      )
  }

  /**
    * Creates the eq spec for the given enum, where `param1` and `param2` are the parameters to the function.
    */
  private def mkEqSpec(enum0: KindedAst.Enum, param1: Symbol.VarSym, param2: Symbol.VarSym, loc: SourceLocation, root: KindedAst.Root): KindedAst.Spec = enum0 match {
    case KindedAst.Enum(_, _, _, _, tparams, _, _, tpe, _) =>
      val eqTraitSym = PredefinedTraits.lookupTraitSym("Eq", root)
      KindedAst.Spec(
        doc = Doc(Nil, loc),
        ann = Annotations.Empty,
        mod = Modifiers.Empty,
        tparams = tparams,
        fparams = List(
          KindedAst.FormalParam(param1, Modifiers.Empty, tpe, TypeSource.Ascribed, loc),
          KindedAst.FormalParam(param2, Modifiers.Empty, tpe, TypeSource.Ascribed, loc)
        ),
        sc = Scheme(
          tparams.map(_.sym),
          List(TraitConstraint(TraitSymUse(eqTraitSym, loc), tpe, loc)),
          Nil,
          Type.mkPureUncurriedArrow(List(tpe, tpe), Type.mkBool(loc), loc)
        ),
        tpe = Type.mkBool(loc),
        eff = Type.Cst(TypeConstructor.Pure, loc),
        tconstrs = List(TraitConstraint(TraitSymUse(eqTraitSym, loc), tpe, loc)),
        econstrs = Nil,
      )
  }

  /**
    * Creates an Eq match rule for the given enum case.
    */
  private def mkEqMatchRule(caze: KindedAst.Case, loc: SourceLocation, root: KindedAst.Root)(implicit flix: Flix): KindedAst.MatchRule = caze match {
    case KindedAst.Case(sym, tpes, _, _) =>
      val eqSym = PredefinedTraits.lookupSigSym("Eq", "eq", root)

      // get a pattern corresponding to this case, e.g.
      // `case C2(x0, x1)`
      val (pat1, varSyms1) = mkPattern(sym, tpes, "x", loc)
      val (pat2, varSyms2) = mkPattern(sym, tpes, "y", loc)
      val pat = KindedAst.Pattern.Tuple(Nel(pat1, List(pat2)), loc)

      // call eq on each variable pair
      // `x0 == y0`, `x1 == y1`
      val eqs = varSyms1.zip(varSyms2).map {
        case (varSym1, varSym2) =>
          KindedAst.Expr.ApplySig(SigSymUse(eqSym, loc),
            List(
              mkVarExpr(varSym1, loc),
              mkVarExpr(varSym2, loc)
            ),
            Type.freshVar(Kind.Star, loc),
            List.empty,
            Type.freshVar(Kind.Star, loc),
            Type.freshVar(Kind.Star, loc),
            Type.freshVar(Kind.Eff, loc),
            loc
          )
      }

      // put it all together
      // `x0 == y0 and x1 == y1`
      val exp = eqs match {
        // Case 1: no arguments: return true
        case Nil => KindedAst.Expr.Cst(Constant.Bool(true), loc)
        // Case 2: at least one argument: join everything with `and`
        case head :: tail => tail.foldLeft(head: KindedAst.Expr) {
          case (acc, eq) => KindedAst.Expr.Binary(SemanticOp.BoolOp.And, acc, eq, Type.freshVar(Kind.Star, loc), loc)
        }
      }

      KindedAst.MatchRule(pat, None, exp, loc)
  }

  /**
    * Creates an Order instance for the given enum.
    *
    * {{{
    * enum E[a] with Order {
    *   case C1
    *   case C2(a)
    *   case C3(a, a)
    * }
    * }}}
    *
    * yields
    *
    * {{{
    * instance Order[E[a]] with Order[a] {
    *   pub def compare(x: E[a], y: E[a]): Comparison = {
    *     let indexOf = e -> match e {
    *       case C0 -> 0
    *       case C1(_) -> 1
    *       case C2(_, _) -> 2
    *     };
    *     match (x, y) {
    *       case (C0, C0) => Comparison.EqualTo
    *       case (C1(x0), C1(y0)) => Order.compare(x0, y0)
    *       case (C2(x0, x1), C2(y0, y1)) => Order.compare(x0, y0) `Order.thenCompare` lazy Order.compare(x1, y1)
    *       case _ => Order.compare(indexOf(x), indexOf(y))
    *     }
    *   }
    * }
    * }}}
    */
  private def mkOrderInstance(enum0: KindedAst.Enum, loc: SourceLocation, root: KindedAst.Root)(implicit flix: Flix): KindedAst.Instance = enum0 match {
    case KindedAst.Enum(_, _, _, _, tparams, _, _, tpe, _) =>
      assert(loc.isSynthetic)

      val orderTraitSym = PredefinedTraits.lookupTraitSym("Order", root)
      val compareDefSym = Symbol.mkDefnSym("Order.compare", Some(flix.genSym.freshId()))

      val param1 = Symbol.freshVarSym("x", BoundBy.FormalParam, loc)
      val param2 = Symbol.freshVarSym("y", BoundBy.FormalParam, loc)
      val exp = mkCompareImpl(enum0, param1, param2, loc, root)
      val spec = mkCompareSpec(enum0, param1, param2, loc, root)

      val defn = KindedAst.Def(compareDefSym, spec, exp, loc)

      val tconstrs = getTraitConstraintsForTypeParams(tparams, orderTraitSym, loc)
      KindedAst.Instance(
        doc = Doc(Nil, loc),
        ann = Annotations.Empty,
        mod = Modifiers.Empty,
        symUse = TraitSymUse(orderTraitSym, loc),
        tparams = tparams,
        tpe = tpe,
        tconstrs = tconstrs,
        econstrs = Nil,
        assocs = Nil,
        defs = List(defn),
        ns = Name.RootNS,
        loc = loc
      )
  }

  /**
    * Creates the compare implementation for the given enum, where `param1` and `param2` are the parameters to the function.
    */
  private def mkCompareImpl(enum0: KindedAst.Enum, param1: Symbol.VarSym, param2: Symbol.VarSym, loc: SourceLocation, root: KindedAst.Root)(implicit flix: Flix): KindedAst.Expr = enum0 match {
    case KindedAst.Enum(_, _, _, _, _, _, cases, _, _) =>
      val compareSigSym = PredefinedTraits.lookupSigSym("Order", "compare", root)

      val lambdaSym = Symbol.freshVarSym("indexOf", BoundBy.Let, loc)

      // Create the lambda mapping tags to indices
      val lambdaParamSym = Symbol.freshVarSym("e", BoundBy.FormalParam, loc)
      val indexMatchRules = getCasesInStableOrder(cases).zipWithIndex.map { case (caze, index) => mkCompareIndexMatchRule(caze, index, loc) }
      val indexMatchExp = KindedAst.Expr.Match(mkVarExpr(lambdaParamSym, loc), indexMatchRules, loc)
      val lambda = KindedAst.Expr.Lambda(
        KindedAst.FormalParam(lambdaParamSym, Modifiers.Empty, lambdaParamSym.tvar, TypeSource.Ascribed, loc),
        indexMatchExp,
        allowSubeffecting = false,
        loc
      )

      // Create the main match expression
      val matchRules = getCasesInStableOrder(cases).map(mkComparePairMatchRule(_, loc, root))

      // Create the default rule:
      // `case _ => compare(indexOf(x), indexOf(y))`
      val defaultMatchRule = KindedAst.MatchRule(
        KindedAst.Pattern.Wild(Type.freshVar(Kind.Star, loc), loc),
        None,
        KindedAst.Expr.ApplySig(
          SigSymUse(compareSigSym, loc),
          List(
            KindedAst.Expr.ApplyClo(
              mkVarExpr(lambdaSym, loc),
              mkVarExpr(param1, loc),
              Type.freshVar(Kind.Star, loc),
              Type.freshVar(Kind.Eff, loc),
              loc
            ),
            KindedAst.Expr.ApplyClo(
              mkVarExpr(lambdaSym, loc),
              mkVarExpr(param2, loc),
              Type.freshVar(Kind.Star, loc),
              Type.freshVar(Kind.Eff, loc),
              loc),
          ),
          Type.freshVar(Kind.Star, loc),
          List.empty,
          Type.freshVar(Kind.Star, loc),
          Type.freshVar(Kind.Star, loc),
          Type.freshVar(Kind.Eff, loc),
          loc
        ),
        loc
      )

      // Wrap the cases in a match expression
      val matchExp = KindedAst.Expr.Match(
        KindedAst.Expr.Tuple(List(mkVarExpr(param1, loc), mkVarExpr(param2, loc)), loc),
        matchRules :+ defaultMatchRule,
        loc
      )

      // Put the expressions together in a let
      KindedAst.Expr.Let(lambdaSym, lambda, matchExp, loc)
  }

  /**
    * Creates the eq spec for the given enum, where `param1` and `param2` are the parameters to the function.
    */
  private def mkCompareSpec(enum0: KindedAst.Enum, param1: Symbol.VarSym, param2: Symbol.VarSym, loc: SourceLocation, root: KindedAst.Root): KindedAst.Spec = enum0 match {
    case KindedAst.Enum(_, _, _, _, tparams, _, _, tpe, _) =>
      val orderTraitSym = PredefinedTraits.lookupTraitSym("Order", root)
      val comparisonEnumSym = PredefinedTraits.lookupEnumSym("Comparison", root)

      KindedAst.Spec(
        doc = Doc(Nil, loc),
        ann = Annotations.Empty,
        mod = Modifiers.Empty,
        tparams = tparams,
        fparams = List(
          KindedAst.FormalParam(param1, Modifiers.Empty, tpe, TypeSource.Ascribed, loc),
          KindedAst.FormalParam(param2, Modifiers.Empty, tpe, TypeSource.Ascribed, loc)
        ),
        sc = Scheme(
          tparams.map(_.sym),
          List(TraitConstraint(TraitSymUse(orderTraitSym, loc), tpe, loc)),
          Nil,
          Type.mkPureUncurriedArrow(List(tpe, tpe), Type.mkEnum(comparisonEnumSym, Kind.Star, loc), loc)
        ),
        tpe = Type.mkEnum(comparisonEnumSym, Kind.Star, loc),
        eff = Type.Cst(TypeConstructor.Pure, loc),
        tconstrs = List(TraitConstraint(TraitSymUse(orderTraitSym, loc), tpe, loc)),
        econstrs = Nil
      )
  }

  /**
    * Creates an indexing match rule, mapping the given case to the given index, e.g.
    * `case C2(_, _) => 2`
    */
  private def mkCompareIndexMatchRule(caze: KindedAst.Case, index: Int, loc: SourceLocation)(implicit Flix: Flix): KindedAst.MatchRule = caze match {
    case KindedAst.Case(sym, tpes, _, _) =>
      val pats = tpes.map(_ => KindedAst.Pattern.Wild(Type.freshVar(Kind.Star, loc), loc))
      val pat = KindedAst.Pattern.Tag(CaseSymUse(sym, loc), pats, Type.freshVar(Kind.Star, loc), loc)
      val exp = KindedAst.Expr.Cst(Constant.Int32(index), loc)
      KindedAst.MatchRule(pat, None, exp, loc)
  }

  /**
    * Creates a comparison match rule, comparing the elements of two tags of the same type.
    * {{{ case (C2(x0, x1), C2(y0, y1)) => compare(x0, y0) thenCompare lazy(x1, y1) }}}
    */
  private def mkComparePairMatchRule(caze: KindedAst.Case, loc: SourceLocation, root: KindedAst.Root)(implicit flix: Flix): KindedAst.MatchRule = caze match {
    case KindedAst.Case(sym, tpes, _, _) =>
      val equalToSym = PredefinedTraits.lookupCaseSym("Comparison", "EqualTo", root)
      val compareSigSym = PredefinedTraits.lookupSigSym("Order", "compare", root)
      val thenCompareDefSym = PredefinedTraits.lookupDefSym(List("Order"), "thenCompare", root)

      // Match on the tuple
      // `case (C2(x0, x1), C2(y0, y1))
      val (pat1, varSyms1) = mkPattern(sym, tpes, "x", loc)
      val (pat2, varSyms2) = mkPattern(sym, tpes, "y", loc)
      val pat = KindedAst.Pattern.Tuple(Nel(pat1, List(pat2)), loc)

      // Call compare on each variable pair
      // `compare(x0, y0)`, `compare(x1, y1)`
      val compares = varSyms1.zip(varSyms2).map {
        case (varSym1, varSym2) =>
          KindedAst.Expr.ApplySig(
            SigSymUse(compareSigSym, loc),
            List(
              mkVarExpr(varSym1, loc),
              mkVarExpr(varSym2, loc)
            ),
            Type.freshVar(Kind.Star, loc),
            List.empty,
            Type.freshVar(Kind.Star, loc),
            Type.freshVar(Kind.Star, loc),
            Type.freshVar(Kind.Eff, loc),
            loc
          )
      }

      /**
        * Joins the two expressions via `Compare.thenCompare`, making the second expression lazy.
        * (Cannot be inlined due to issues with Scala's type inference.
        */
      def thenCompare(exp1: KindedAst.Expr, exp2: KindedAst.Expr): KindedAst.Expr = {
        KindedAst.Expr.ApplyDef(
          DefSymUse(thenCompareDefSym, loc),
          List(
            exp1,
            KindedAst.Expr.Lazy(exp2, loc)
          ),
          List.empty,
          Type.freshVar(Kind.Star, loc),
          Type.freshVar(Kind.Star, loc),
          Type.freshVar(Kind.Eff, loc),
          loc
        )
      }

      // Put it all together
      // compare(x0, y0) `thenCompare` lazy compare(x1, y1)
      val exp = compares match {
        // Case 1: no variables to compare; just return true
        case Nil => KindedAst.Expr.Tag(CaseSymUse(equalToSym, loc), Nil, Type.freshVar(Kind.Star, loc), loc)
        // Case 2: multiple comparisons to be done; wrap them in Order.thenCompare
        case cmps => cmps.reduceRight(thenCompare)
      }

      KindedAst.MatchRule(pat, None, exp, loc)
  }

  /**
    * Creates a ToString instance for the given enum.
    *
    * {{{
    * enum E[a] with ToString {
    *   case C0
    *   case C1(a)
    *   case C2(a, a)
    * }
    * }}}
    *
    * yields
    *
    * {{{
    * instance ToString[E[a]] with ToString[a] {
    *   pub def toString(x: E[a]): String = match x {
    *     case C0 => "C0"
    *     case C1(x0) => "C1" + "(" + ToString.toString(x0) + ")"
    *     case C2(x0, x1) => "C2" + "(" + ToString.toString(x0) + ", " + ToString.toString(x1) + ")"
    *   }
    * }
    * }}}
    */
  private def mkToStringInstance(enum0: KindedAst.Enum, loc: SourceLocation, root: KindedAst.Root)(implicit flix: Flix): KindedAst.Instance = enum0 match {
    case KindedAst.Enum(_, _, _, _, tparams, _, _, tpe, _) =>
      assert(loc.isSynthetic)

      val toStringTraitSym = PredefinedTraits.lookupTraitSym("ToString", root)
      val toStringDefSym = Symbol.mkDefnSym("ToString.toString", Some(flix.genSym.freshId()))

      val param = Symbol.freshVarSym("x", BoundBy.FormalParam, loc)
      val exp = mkToStringImpl(enum0, param, loc, root)
      val spec = mkToStringSpec(enum0, param, loc, root)

      val defn = KindedAst.Def(toStringDefSym, spec, exp, loc)

      val tconstrs = getTraitConstraintsForTypeParams(tparams, toStringTraitSym, loc)

      KindedAst.Instance(
        doc = Doc(Nil, loc),
        ann = Annotations.Empty,
        mod = Modifiers.Empty,
        symUse = TraitSymUse(toStringTraitSym, loc),
        tparams = tparams,
        tpe = tpe,
        tconstrs = tconstrs,
        econstrs = Nil,
        assocs = Nil,
        defs = List(defn),
        ns = Name.RootNS,
        loc = loc
      )
  }

  /**
    * Creates the toString implementation for the given enum, where `param` is the parameter to the function.
    */
  private def mkToStringImpl(enum0: KindedAst.Enum, param: Symbol.VarSym, loc: SourceLocation, root: KindedAst.Root)(implicit flix: Flix): KindedAst.Expr = enum0 match {
    case KindedAst.Enum(_, _, _, _, _, _, cases, _, _) =>
      // create a match rule for each case
      val matchRules = getCasesInStableOrder(cases).map(mkToStringMatchRule(_, loc, root))

      // group the match rules in an expression
      KindedAst.Expr.Match(
        mkVarExpr(param, loc),
        matchRules,
        loc
      )
  }

  /**
    * Creates the toString spec for the given enum, where `param` is the parameter to the function.
    */
  private def mkToStringSpec(enum0: KindedAst.Enum, param: Symbol.VarSym, loc: SourceLocation, root: KindedAst.Root): KindedAst.Spec = enum0 match {
    case KindedAst.Enum(_, _, _, _, tparams, _, _, tpe, _) =>
      val toStringTraitSym = PredefinedTraits.lookupTraitSym("ToString", root)
      KindedAst.Spec(
        doc = Doc(Nil, loc),
        ann = Annotations.Empty,
        mod = Modifiers.Empty,
        tparams = tparams,
        fparams = List(KindedAst.FormalParam(param, Modifiers.Empty, tpe, TypeSource.Ascribed, loc)),
        sc = Scheme(
          tparams.map(_.sym),
          List(TraitConstraint(TraitSymUse(toStringTraitSym, loc), tpe, loc)),
          Nil,
          Type.mkPureArrow(tpe, Type.mkString(loc), loc)
        ),
        tpe = Type.mkString(loc),
        eff = Type.Cst(TypeConstructor.Pure, loc),
        tconstrs = List(TraitConstraint(TraitSymUse(toStringTraitSym, loc), tpe, loc)),
        econstrs = Nil
      )
  }

  /**
    * Creates a ToString match rule for the given enum case.
    */
  private def mkToStringMatchRule(caze: KindedAst.Case, loc: SourceLocation, root: KindedAst.Root)(implicit flix: Flix): KindedAst.MatchRule = caze match {
    case KindedAst.Case(sym, tpes, _, _) =>
      val toStringSym = PredefinedTraits.lookupSigSym("ToString", "toString", root)

      // get a pattern corresponding to this case, e.g.
      // `case C2(x0, x1)`
      val (pat, varSyms) = mkPattern(sym, tpes, "x", loc)

      // "C2"
      val tagPart = KindedAst.Expr.Cst(Constant.Str(sym.name), loc)

      // call toString on each variable,
      // `toString(x0)`, `toString(x1)`
      val toStrings = varSyms.map {
        varSym =>
          KindedAst.Expr.ApplySig(
            SigSymUse(toStringSym, loc),
            List(mkVarExpr(varSym, loc)),
            Type.freshVar(Kind.Star, loc),
            List.empty,
            Type.freshVar(Kind.Star, loc),
            Type.freshVar(Kind.Star, loc),
            Type.freshVar(Kind.Eff, loc),
            loc
          )
      }

      // put commas between the arguments
      // `toString(x0)`, `", "`, `toString(x1)`
      val sep = mkStrExpr(", ", loc)
      val valuePart = intersperse(toStrings, sep)

      // put it all together
      // `"C2" + "(" + toString(x0) + ", " + toString(x1) + ")"`
      val exp = valuePart match {
        // Case 1: no arguments: just show the tag
        case Nil => tagPart
        // Case 2: at least one argument: concatenate the tag with the values wrapped in parens
        case exps => concatAll(tagPart :: mkStrExpr("(", loc) :: (exps :+ mkStrExpr(")", loc)), loc)
      }

      KindedAst.MatchRule(pat, None, exp, loc)
  }

  /**
    * Creates a Hash instance for the given enum.
    *
    * {{{
    * enum E[a] with Hash {
    *   case C0
    *   case C1(a)
    *   case C2(a, a)
    * }
    * }}}
    *
    * yields
    *
    * {{{
    * instance Hash[E[a]] with Hash[a] {
    *   pub def hash(x: E[a]): Int = match x {
    *     case C0 => 1
    *     case C1(x0) => 2 `combine` hash(x0)
    *     case C2(x0, x1) => 3 `combine` hash(x0) `combine` hash(x1)
    *   }
    * }
    * }}}
    */
  private def mkHashInstance(enum0: KindedAst.Enum, loc: SourceLocation, root: KindedAst.Root)(implicit flix: Flix): KindedAst.Instance = enum0 match {
    case KindedAst.Enum(_, _, _, _, tparams, _, _, tpe, _) =>
      assert(loc.isSynthetic)

      val hashTraitSym = PredefinedTraits.lookupTraitSym("Hash", root)
      val hashDefSym = Symbol.mkDefnSym("Hash.hash", Some(flix.genSym.freshId()))

      val param = Symbol.freshVarSym("x", BoundBy.FormalParam, loc)
      val exp = mkHashImpl(enum0, param, loc, root)
      val spec = mkHashSpec(enum0, param, loc, root)

      val defn = KindedAst.Def(hashDefSym, spec, exp, loc)

      val tconstrs = getTraitConstraintsForTypeParams(tparams, hashTraitSym, loc)
      KindedAst.Instance(
        doc = Doc(Nil, loc),
        ann = Annotations.Empty,
        mod = Modifiers.Empty,
        symUse = TraitSymUse(hashTraitSym, loc),
        tparams = tparams,
        tpe = tpe,
        tconstrs = tconstrs,
        econstrs = Nil,
        defs = List(defn),
        assocs = Nil,
        ns = Name.RootNS,
        loc = loc
      )
  }

  /**
    * Creates the hash implementation for the given enum, where `param` is the parameter to the function.
    */
  private def mkHashImpl(enum0: KindedAst.Enum, param: Symbol.VarSym, loc: SourceLocation, root: KindedAst.Root)(implicit flix: Flix): KindedAst.Expr = enum0 match {
    case KindedAst.Enum(_, _, _, _, _, _, cases, _, _) =>
      // create a match rule for each case
      val matchRules = getCasesInStableOrder(cases).zipWithIndex.map {
        case (caze, index) => mkHashMatchRule(caze, index, loc, root)
      }

      // group the match rules in an expression
      KindedAst.Expr.Match(
        mkVarExpr(param, loc),
        matchRules,
        loc
      )
  }

  /**
    * Creates the hash spec for the given enum, where `param` is the parameter to the function.
    */
  private def mkHashSpec(enum0: KindedAst.Enum, param: Symbol.VarSym, loc: SourceLocation, root: KindedAst.Root): KindedAst.Spec = enum0 match {
    case KindedAst.Enum(_, _, _, _, tparams, _, _, tpe, _) =>
      val hashTraitSym = PredefinedTraits.lookupTraitSym("Hash", root)
      KindedAst.Spec(
        doc = Doc(Nil, loc),
        ann = Annotations.Empty,
        mod = Modifiers.Empty,
        tparams = tparams,
        fparams = List(KindedAst.FormalParam(param, Modifiers.Empty, tpe, TypeSource.Ascribed, loc)),
        sc = Scheme(
          tparams.map(_.sym),
          List(TraitConstraint(TraitSymUse(hashTraitSym, loc), tpe, loc)),
          Nil,
          Type.mkPureArrow(tpe, Type.mkInt32(loc), loc)
        ),
        tpe = Type.mkInt32(loc),
        eff = Type.Cst(TypeConstructor.Pure, loc),
        tconstrs = List(TraitConstraint(TraitSymUse(hashTraitSym, loc), tpe, loc)),
        econstrs = Nil
      )
  }

  /**
    * Creates a Hash match rule for the given enum case.
    */
  private def mkHashMatchRule(caze: KindedAst.Case, index: Int, loc: SourceLocation, root: KindedAst.Root)(implicit flix: Flix): KindedAst.MatchRule = caze match {
    case KindedAst.Case(sym, tpe, _, _) =>
      val hashSigSym = PredefinedTraits.lookupSigSym("Hash", "hash", root)
      val combineDefSym = PredefinedTraits.lookupDefSym(List("Hash"), "combine", root)

      // get a pattern corresponding to this case, e.g.
      // `case C2(x0, x1)`
      val (pat, varSyms) = mkPattern(sym, tpe, "x", loc)

      // build a hash code by repeatedly adding elements via the combine function
      // the first hash is the index + 1
      // `3 `combine` hash(x0) `combine` hash(y0)`
      val exp = varSyms.foldLeft(KindedAst.Expr.Cst(Constant.Int32(index + 1), loc): KindedAst.Expr) {
        case (acc, varSym) =>
          // `acc `combine` hash(varSym)
          KindedAst.Expr.ApplyDef(
            DefSymUse(combineDefSym, loc),
            List(
              acc,
              KindedAst.Expr.ApplySig(
                SigSymUse(hashSigSym, loc),
                List(mkVarExpr(varSym, loc)),
                Type.freshVar(Kind.Star, loc),
                List.empty,
                Type.freshVar(Kind.Star, loc),
                Type.freshVar(Kind.Star, loc),
                Type.freshVar(Kind.Eff, loc),
                loc
              ),
            ),
            List.empty,
            Type.freshVar(Kind.Star, loc),
            Type.freshVar(Kind.Star, loc),
            Type.freshVar(Kind.Eff, loc),
            loc
          )
      }

      KindedAst.MatchRule(pat, None, exp, loc)
  }

  /**
    * Creates a Coerce instance for the given enum.
    *
    * The enum must be a singleton.
    *
    * {{{
    * enum Box[a](a, String)
    * }}}
    *
    * yields
    *
    * {{{
    * instance Coerce[Box[a]] {
    *   type Out = (a, String)
    *   pub def coerce(x: Box[a]): (a, String) = {
    *     match x {
    *       case Box.Box(y) => y
    *     }
    *   }
    * }}}
    */
  private def mkCoerceInstance(enum0: KindedAst.Enum, loc: SourceLocation, root: KindedAst.Root)(implicit sctx: SharedContext, flix: Flix): Option[KindedAst.Instance] = enum0 match {
    case KindedAst.Enum(_, _, _, sym, tparams, _, cases, tpe, _) =>
      assert(loc.isSynthetic)

      if (cases.size == 1) {
        val coerceTraitSym = PredefinedTraits.lookupTraitSym("Coerce", root)
        val coerceDefSym = Symbol.mkDefnSym("Coerce.coerce", Some(flix.genSym.freshId()))

        val (_, caze) = cases.head

        val outSym = new Symbol.AssocTypeSym(coerceTraitSym, "Out", loc)
        val outTpe = Type.mkTuplish(caze.tpes, loc)
        val out = KindedAst.AssocTypeDef(
          Doc(Nil, loc),
          Modifiers.Empty,
          AssocTypeSymUse(outSym, loc),
          tpe,
          outTpe,
          loc
        )

        val param = Symbol.freshVarSym("x", BoundBy.FormalParam, loc)
        val exp = mkCoerceImpl(enum0, param, loc)
        val spec = mkCoerceSpec(enum0, param, loc, root)

        val defn = KindedAst.Def(coerceDefSym, spec, exp, loc)

        Some(KindedAst.Instance(
          doc = Doc(Nil, loc),
          ann = Annotations.Empty,
          mod = Modifiers.Empty,
          symUse = TraitSymUse(coerceTraitSym, loc),
          tparams = tparams,
          tpe = tpe,
          tconstrs = Nil,
          econstrs = Nil,
          defs = List(defn),
          assocs = List(out),
          ns = Name.RootNS,
          loc = loc
        ))
      } else {
        val error = DerivationError.IllegalNonSingletonCoerce(sym, loc)
        sctx.errors.add(error)
        None
      }
  }

  /**
    * Creates the coerce implementation for the given enum.
    */
  private def mkCoerceImpl(enum0: KindedAst.Enum, param: Symbol.VarSym, loc: SourceLocation)(implicit flix: Flix): KindedAst.Expr = enum0 match {
    case KindedAst.Enum(_, _, _, _, _, _, cases, _, _) =>
      val (_, caze) = cases.head
      val matchRule = mkCoerceMatchRule(caze, loc)

      KindedAst.Expr.Match(
        KindedAst.Expr.Var(param, loc),
        List(matchRule),
        loc
      )
  }

  /**
    * Creates the coerce specification for the given enum.
    */
  private def mkCoerceSpec(enum0: KindedAst.Enum, param: Symbol.VarSym, loc: SourceLocation, root: KindedAst.Root): KindedAst.Spec = enum0 match {
    case KindedAst.Enum(_, _, _, _, tparams, _, cases, tpe, _) =>
      val coerceTraitSym = PredefinedTraits.lookupTraitSym("Coerce", root)
      val (_, caze) = cases.head
      val retTpe = Type.mkTuplish(caze.tpes, loc)
      KindedAst.Spec(
        doc = Doc(Nil, loc),
        ann = Annotations.Empty,
        mod = Modifiers.Empty,
        tparams = tparams,
        fparams = List(KindedAst.FormalParam(param, Modifiers.Empty, tpe, TypeSource.Ascribed, loc)),
        sc = Scheme(
          tparams.map(_.sym),
          List(TraitConstraint(TraitSymUse(coerceTraitSym, loc), tpe, loc)),
          Nil,
          Type.mkPureArrow(tpe, retTpe, loc)
        ),
        tpe = retTpe,
        eff = Type.Cst(TypeConstructor.Pure, loc),
        tconstrs = List(TraitConstraint(TraitSymUse(coerceTraitSym, loc), tpe, loc)),
        econstrs = Nil
      )
  }

  /**
    * Creates a Coerce match rule for the given enum case.
    */
  private def mkCoerceMatchRule(caze: KindedAst.Case, loc: SourceLocation)(implicit flix: Flix): KindedAst.MatchRule = caze match {
    case KindedAst.Case(sym, tpes, _, _) =>
      // get a pattern corresponding to this case, e.g.
      // `case C(x0)`
      val (pat, varSyms) = mkPattern(sym, tpes, "x", loc)

      // the body is a tuplish of whatever we extracted
      val vars = varSyms.map(KindedAst.Expr.Var(_, loc))
      val exp = vars match {
        case Nil => KindedAst.Expr.Cst(Constant.Unit, loc)
        case x :: Nil => x
        case xs@(_ :: _ :: _) => KindedAst.Expr.Tuple(xs, loc)
      }

      KindedAst.MatchRule(pat, None, exp, loc)
  }

  /**
    * Returns the cases in `m` in a *stable order* that relies on the order of their source locations.
    */
  private def getCasesInStableOrder(m: Map[Symbol.CaseSym, KindedAst.Case]): List[KindedAst.Case] = {
    m.values.toList.sortBy(_.loc)
  }

  /**
    * Creates trait constraints for the given type parameters.
    * Filters out non-star type parameters and wild type parameters.
    */
  private def getTraitConstraintsForTypeParams(tparams: List[KindedAst.TypeParam], trt: Symbol.TraitSym, loc: SourceLocation): List[TraitConstraint] = tparams.collect {
    case tparam if tparam.sym.kind == Kind.Star && !tparam.name.isWild =>
      TraitConstraint(TraitSymUse(trt, loc), Type.Var(tparam.sym, loc), loc)
  }

  /**
    * Builds a string expression from the given string.
    */
  private def mkStrExpr(str: String, loc: SourceLocation): KindedAst.Expr = KindedAst.Expr.Cst(Constant.Str(str), loc)

  /**
    * Builds a var expression from the given var sym.
    */
  private def mkVarExpr(sym: Symbol.VarSym, loc: SourceLocation): KindedAst.Expr.Var = KindedAst.Expr.Var(sym, loc)

  /**
    * Builds a string concatenation expression from the given expressions.
    */
  private def concat(exp1: KindedAst.Expr, exp2: KindedAst.Expr, loc: SourceLocation)(implicit flix: Flix): KindedAst.Expr = {
    KindedAst.Expr.Binary(SemanticOp.StringOp.Concat, exp1, exp2, Type.freshVar(Kind.Star, loc), loc)
  }

  /**
    * Builds a string concatenation expression from the given expressions.
    */
  private def concatAll(exps: List[KindedAst.Expr], loc: SourceLocation)(implicit flix: Flix): KindedAst.Expr = {
    exps match {
      case Nil => KindedAst.Expr.Cst(Constant.Str(""), loc)
      case head :: tail => tail.foldLeft(head)(concat(_, _, loc))
    }
  }

  /**
    * Creates a pattern corresponding to the given tag type.
    */
  private def mkPattern(sym: Symbol.CaseSym, tpes: List[Type], varPrefix: String, loc: SourceLocation)(implicit flix: Flix): (KindedAst.Pattern, List[Symbol.VarSym]) = {
    val varSyms = tpes.zipWithIndex.map { case (_, index) => Symbol.freshVarSym(s"$varPrefix$index", BoundBy.Pattern, loc) }
    val subPats = varSyms.map(varSym => mkVarPattern(varSym, loc))
    (KindedAst.Pattern.Tag(CaseSymUse(sym, loc), subPats, Type.freshVar(Kind.Star, loc), loc), varSyms)
  }

  /**
    * Creates a variable pattern using the given variable symbol.
    */
  private def mkVarPattern(sym: Symbol.VarSym, loc: SourceLocation): KindedAst.Pattern = KindedAst.Pattern.Var(sym, sym.tvar, loc)

  /**
    * Inserts `sep` between every two elements of `list`.
    */
  private def intersperse[A](list: List[A], sep: A): List[A] = list match {
    case Nil => Nil
    case last :: Nil => last :: Nil
    case head :: neck :: tail => head :: sep :: intersperse(neck :: tail, sep)
  }

  /**
    * Companion object for [[SharedContext]]
    */
  private object SharedContext {
    /**
      * Returns a fresh shared context.
      */
    def mk(): SharedContext = new SharedContext(new ConcurrentLinkedQueue())
  }

  /**
    * A global shared context. Must be thread-safe.
    *
    * @param errors the [[DerivationError]]s in the AST, if any.
    */
  private case class SharedContext(errors: ConcurrentLinkedQueue[DerivationError])

}
