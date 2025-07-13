package ca.uwaterloo.flix.language.phase.unification

import ca.uwaterloo.flix.language.ast.{Symbol, Type}
import ca.uwaterloo.flix.util.collection.Bimap

object Context {
  /**
    * Returns the empty substitution.
    */
  val empty: Context = Context(Substitution(Map.empty), Bimap.empty)

  /**
    * Returns the singleton substitution mapping the type variable `x` to `tpe`.
    */
  def singleton(x: Symbol.KindedTypeVarSym, tpe: Type): Context = {
    Context(Substitution.singleton(x, tpe), Bimap.empty)
  }
}

case class Context(s: Substitution, eq: Bimap[Type,Type]) {
}
