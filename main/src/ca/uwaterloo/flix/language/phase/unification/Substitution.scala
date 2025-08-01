/*
 *  Copyright 2020 Magnus Madsen
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package ca.uwaterloo.flix.language.phase.unification

import ca.uwaterloo.flix.language.ast.shared.{EqualityConstraint, TraitConstraint}
import ca.uwaterloo.flix.language.ast.{Scheme, Symbol, Type}
import ca.uwaterloo.flix.util.InternalCompilerException

/**
  * Companion object for the [[Substitution]] class.
  */
object Substitution {
  /**
    * Returns the empty substitution.
    */
  val empty: Substitution = Substitution(Map.empty)

  /**
    * Returns the singleton substitution mapping the type variable `x` to `tpe`.
    */
  def singleton(x: Symbol.KindedTypeVarSym, tpe: Type): Substitution = {
    // Ensure that we do not add any x -> x mappings.
    tpe match {
      case y: Type.Var if x.id == y.sym.id => empty
      case y: Type.Var if y.sym.text isStrictlyLessPreciseThan x.text => Substitution(Map(x -> y.withText(x.text)))
      case y: Type.Var if x.text isStrictlyLessPreciseThan y.sym.text => Substitution(Map(x.withText(y.sym.text) -> y))
      case _ => Substitution(Map(x -> tpe))
    }
  }

}

/**
  * A substitution is a map from type variables to types.
  */
case class Substitution(m: Map[Symbol.KindedTypeVarSym, Type]) {

  /**
    * Returns `true` if `this` is the empty substitution.
    */
  val isEmpty: Boolean = m.isEmpty

  /**
    * Applies `this` substitution to the given type `tpe0`.
    */
  def apply(tpe0: Type): Type = {
    // Optimization: Return the type if the substitution is empty. Otherwise visit the type.
    if (isEmpty) tpe0 else visitType(tpe0)
  }

  private def visitType(t: Type): Type = t match {
    // NB: The order of cases has been determined by code coverage analysis.
    case x: Type.Var => m.getOrElse(x.sym, x)

    case Type.Cst(_, _) => t

    case app@Type.Apply(t1, t2, loc) =>
      // Note: While we could perform simplifications here,
      // experimental results have shown that it is not worth it.
      val x = visitType(t1)
      val y = visitType(t2)
      // Performance: Reuse t, if possible.
      app.renew(x, y, loc)

    case Type.Alias(sym, args0, tpe0, loc) =>
      val args = args0.map(visitType)
      val tpe = visitType(tpe0)
      Type.Alias(sym, args, tpe, loc)

    case Type.AssocType(cst, args0, kind, loc) =>
      val args = args0.map(visitType)
      Type.AssocType(cst, args, kind, loc)

    case Type.JvmToType(tpe0, loc) =>
      val tpe = visitType(tpe0)
      Type.JvmToType(tpe, loc)

    case Type.JvmToEff(tpe0, loc) =>
      val tpe = visitType(tpe0)
      Type.JvmToEff(tpe, loc)

    case Type.UnresolvedJvmType(member0, loc) =>
      val member = member0.map(visitType)
      Type.UnresolvedJvmType(member, loc)
  }


  /**
    * Applies `this` substitution to the given types `ts`.
    */
  def apply(ts: List[Type]): List[Type] = if (isEmpty) ts else ts map apply

  /**
    * Applies `this` substitution to the given type constraint `tc`.
    */
  def apply(tc: TraitConstraint): TraitConstraint = if (isEmpty) tc else tc.copy(arg = apply(tc.arg))

  /**
    * Applies `this` substitution to the given type scheme `sc`.
    *
    * NB: Throws an InternalCompilerException if quantifiers are present in the substitution.
    */
  def apply(sc: Scheme): Scheme = sc match {
    case Scheme(quantifiers, tconstrs, econstrs, base) =>
      if (sc.quantifiers.exists(m.contains)) {
        throw InternalCompilerException("Quantifier in substitution.", base.loc)
      }
      Scheme(quantifiers, tconstrs.map(apply), econstrs.map(apply), apply(base))
  }

  /**
    * Applies `this` substitution to the given equality constraint.
    */
  def apply(ec: EqualityConstraint): EqualityConstraint = if (isEmpty) ec else ec match {
    case EqualityConstraint(cst, t1, t2, loc) => EqualityConstraint(cst, apply(t1), apply(t2), loc)
  }

  /**
    * Returns the left-biased composition of `this` substitution with `that` substitution.
    */
  def ++(that: Substitution): Substitution = {
    if (this.isEmpty) {
      that
    } else if (that.isEmpty) {
      this
    } else {
      Substitution(
        this.m ++ that.m.filter(kv => !this.m.contains(kv._1))
      )
    }
  }

  /**
    * Returns the composition of `this` substitution with `that` substitution.
    */
  def @@(that: Substitution): Substitution = {
    // Case 1: Return `that` if `this` is empty.
    if (this.isEmpty) {
      return that
    }

    // Case 2: Return `this` if `that` is empty.
    if (that.isEmpty) {
      return this
    }

    // Case 3: Merge the two substitutions.

    // Add all bindings in `that`. (Applying the current substitution).
    var result = that.m
    for ((x, tpe) <- that.m) {
      val t = this.apply(tpe)
      if (!(t eq tpe)) {
        result = result.updated(x, t)
      }
    }

    // Add all bindings in `this` that are not in `that`.
    for ((x, tpe) <- this.m) {
      if (!that.m.contains(x)) {
        result = result.updated(x, tpe)
      }
    }

    Substitution(result)
  }

  /**
    * Returns the size of the largest type in this substitution.
    */
  def maxSize: Int = m.values.map(_.size).maxOption.getOrElse(0)
}
