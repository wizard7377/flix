/*
 *  Copyright 2019 Magnus Madsen
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

package ca.uwaterloo.flix.language.errors

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.TypedAst.ExtPattern
import ca.uwaterloo.flix.language.{CompilationMessage, CompilationMessageKind}
import ca.uwaterloo.flix.language.ast.shared.TraitConstraint
import ca.uwaterloo.flix.language.ast.{Name, SourceLocation, Symbol, Type, TypeConstructor}
import ca.uwaterloo.flix.language.fmt.{FormatTraitConstraint, FormatType}
import ca.uwaterloo.flix.util.Formatter

/**
  * A common super-type for redundancy errors.
  */
trait RedundancyError extends CompilationMessage {
  val kind: CompilationMessageKind = CompilationMessageKind.RedundancyError
}

object RedundancyError {

  /**
    * An error raised to indicate that the result of a pure expression is discarded.
    *
    * @param loc the location of the expression.
    */
  case class DiscardedPureValue(loc: SourceLocation) extends RedundancyError {
    def summary: String = "A pure expression should not be discarded."

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> A pure expression should not be discarded.
         |
         |${code(loc, "pure expression.")}
         |""".stripMargin
    }
  }

  /**
    * An error raised to indicate that the extensible variant constructor `label` was used multiple times.
    *
    * @param label    the name of the extensible variant constructor.
    * @param loc1     the location of the first pattern.
    * @param loc2     the location of the second pattern.
    */
  case class DuplicateExtPattern(label: Name.Label, loc1: SourceLocation, loc2: SourceLocation) extends RedundancyError {
    def summary: String = s"Duplicate extensible variant pattern '${label.name}'."

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Duplicate extensible pattern '${red(label.name)}'.
         |
         |${code(loc1, "the first occurrence was here.")}
         |
         |${code(loc2, "the second occurrence was here.")}
         |""".stripMargin
    }

    def loc: SourceLocation = loc1
  }

  /**
    * An error raised to indicate that the variable symbol `sym` is hidden.
    *
    * @param sym the hidden variable symbol.
    * @param loc the source location of the use.
    */
  case class HiddenVarSym(sym: Symbol.VarSym, loc: SourceLocation) extends RedundancyError {
    def summary: String = "Hidden variable symbol."

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Hidden variable symbol '${red(sym.text)}'. The symbol is marked as unused.
         |
         |${code(loc, "hidden symbol.")}
         |""".stripMargin

    }

    override def explain(formatter: Formatter): Option[String] = Some({
      s"""
         |Possible fixes:
         |
         |  (1)  Don't use the variable symbol.
         |  (2)  Rename the underscore prefix from the variable symbol name.
         |
         |""".stripMargin
    })
  }

  /**
    * An error raised to indicate that a checked effect cast is redundant.
    *
    * @param loc the source location of the cast.
    */
  case class RedundantCheckedEffectCast(loc: SourceLocation) extends RedundancyError {
    def summary: String = "Redundant effect cast. The expression already has the required effect."

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Redundant effect cast. The expression already has the required effect.
         |
         |${code(loc, "redundant cast.")}
         |
         |""".stripMargin
    }
  }

  /**
    * An error raised to indicate that a checked type cast is redundant.
    *
    * @param loc the source location of the cast.
    */
  case class RedundantCheckedTypeCast(loc: SourceLocation) extends RedundancyError {
    def summary: String = "Redundant type cast. The expression already has the required type."

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Redundant type cast. The expression already has the required type.
         |
         |${code(loc, "redundant cast.")}
         |
         |""".stripMargin
    }
  }

  /**
    * An error raised to indicate a redundant discard of a unit value.
    *
    * @param loc the location of the inner expression.
    */
  case class RedundantDiscard(loc: SourceLocation) extends RedundancyError {
    def summary: String = "Redundant discard of unit value."

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Redundant discard of unit value.
         |
         |${code(loc, "discarded unit value.")}
         |""".stripMargin
    }
  }

  /**
    * An error raised to indicate a redundant trait constraint.
    *
    * @param entailingTconstr the tconstr that entails the other.
    * @param redundantTconstr the tconstr that is made redundant by the other.
    * @param loc              the location where the error occured.
    */
  case class RedundantTraitConstraint(entailingTconstr: TraitConstraint, redundantTconstr: TraitConstraint, loc: SourceLocation)(implicit flix: Flix) extends RedundancyError {
    def summary: String = "Redundant type constraint."

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Type constraint '${red(FormatTraitConstraint.formatTraitConstraint(redundantTconstr))}' is entailed by type constraint '${green(FormatTraitConstraint.formatTraitConstraint(redundantTconstr))}'.
         |
         |${code(loc, "redundant type constraint.")}
         |""".stripMargin
    }

    override def explain(formatter: Formatter): Option[String] = Some({
      s"""
         |Possible fixes:
         |
         |  (1)  Remove the type constraint.
         |
         |""".stripMargin
    })
  }

  /**
    * An error raised to indicate that an effect cast is redundant.
    *
    * @param loc the source location of the cast.
    */
  case class RedundantUncheckedEffectCast(loc: SourceLocation) extends RedundancyError {
    def summary: String = "Redundant effect cast. The expression is already pure."

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Redundant effect cast. The expression is already pure.
         |
         |${code(loc, "redundant cast.")}
         |
         |""".stripMargin
    }
  }

  /**
    * An error raised to indicate that `unsafely {} run exp` was used.
    *
    * @param loc the source location of the unsafe run.
    */
  case class UselessUnsafe(loc: SourceLocation) extends RedundancyError {
    def summary: String = "Redundant effect removal, it is removing nothing."

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Redundant effect removal, it is removing nothing.
         |
         |${code(loc, "redundant unsafe run.")}
         |
         |""".stripMargin
    }
  }

  /**
    * An error raised to indicate that unsafely run was used on a pure expression.
    *
    * @param loc the source location of the unsafe run.
    */
  case class RedundantUnsafe(loc: SourceLocation) extends RedundancyError {
    def summary: String = "Redundant unsafe run, the expression is pure."

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Redundant unsafe run, the expression is pure.
         |
         |${code(loc, "redundant unsafe run.")}
         |
         |""".stripMargin
    }
  }

  /**
    * An error raised to indicate that a name has been shadowed.
    *
    * @param shadowed  the shadowed name.
    * @param shadowing the shadowing name.
    */
  case class ShadowedName(name: String, shadowed: SourceLocation, shadowing: SourceLocation) extends RedundancyError {
    def summary: String = "Shadowed name."

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Shadowed name '${red(name)}'.
         |
         |${code(shadowed, "shadowed name.")}
         |
         |The shadowing name was declared here:
         |
         |${code(shadowing, "shadowing name.")}
         |
         |""".stripMargin
    }

    def loc: SourceLocation = shadowed
  }

  /**
    * An error raised to indicate that a name is shadowing another name.
    *
    * @param shadowed  the shadowed name.
    * @param shadowing the shadowing name.
    */
  case class ShadowingName(name: String, shadowed: SourceLocation, shadowing: SourceLocation) extends RedundancyError {
    def summary: String = "Shadowing name."

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Shadowing name '${red(name)}'.
         |
         |${code(shadowing, "shadowing name.")}
         |
         |The shadowed name was declared here:
         |
         |${code(shadowed, "shadowed name.")}
         |
         |""".stripMargin
    }

    def loc: SourceLocation = shadowing
  }

  /**
    * An error raised to indicate that an impure function expression is useless
    * is statement position.
    *
    * @param tpe the type of the expression.
    * @param loc the location of the expression.
    */
  case class UnderAppliedFunction(tpe: Type, loc: SourceLocation)(implicit flix: Flix) extends RedundancyError {
    def summary: String = "Under applied function. Missing function argument(s)?"

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Under applied function. ${applicationAdvice(tpe)}
         |
         |${code(loc, "the function is not fully-applied and hence has no effect.")}
         |
         |The function has type '${FormatType.formatType(tpe)}'
         |""".stripMargin
    }

    override def explain(formatter: Formatter): Option[String] = Some({
      s"""
         |Possible fixes:
         |
         |  (1)  Give the function (additional) arguments.
         |  (2)  Use the result computed by the expression.
         |  (3)  Remove the expression statement.
         |  (4)  Introduce a let-binding with a wildcard name.
         |
         |""".stripMargin
    })

    /**
      * Creates an advice string about applied the arguments of the curried arrow `tpe`.
      *
      * OBS: If `tpe` is not arrow type then an exception is thrown.
      */
    private def applicationAdvice(tpe: Type): String = {
      val arguments = curriedArrowArgTypes(tpe)
      if (arguments.isEmpty) { // fallback message
        "Missing function argument(s)?"
      } else {
        val argumentStrings = arguments.map(t => s"${FormatType.formatType(t)}").mkString(", ")
        s"Missing argument(s) of type: $argumentStrings."
      }
    }

    /**
      * Returns the argument types of `this` curried arrow type.
      * Returns `Nil` if `this` is not an arrow type.
      *
      * For example,
      *
      * {{{
      * Int32                               =>     Nil
      * Int32 -> String -> Int32            =>     List(Int32, String)
      * (Int32, String) -> String -> Bool   =>     List(Int32, String, String)
      * }}}
      */
    private def curriedArrowArgTypes(tpe: Type): List[Type] = tpe.typeConstructor match {
      case Some(TypeConstructor.Arrow(_)) => tpe.arrowArgTypes ++ curriedArrowArgTypes(tpe.arrowResultType)
      case _ => Nil
    }
  }

  /**
    * An error raised to indicate that the def with the symbol `sym` is not used.
    *
    * @param sym the unused def symbol.
    */
  case class UnusedDefSym(sym: Symbol.DefnSym) extends RedundancyError {
    def summary: String = "Unused definition."

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Unused definition '${red(sym.name)}'. The definition is never referenced.
         |
         |${code(sym.loc, "unused definition.")}
         |""".stripMargin
    }

    override def explain(formatter: Formatter): Option[String] = Some({
      s"""Possible fixes:
         |
         |  (1)  Use the definition.
         |  (2)  Remove the definition.
         |  (3)  Mark the definition as public.
         |  (4)  Prefix the definition name with an underscore.
         |
         |""".stripMargin
    })

    def loc: SourceLocation = sym.loc
  }

  /**
    * An error raised to indicate that the effect with the symbol `sym` is not used.
    *
    * @param sym the unused effect symbol.
    */
  case class UnusedEffSym(sym: Symbol.EffSym) extends RedundancyError {
    def summary: String = s"Unused effect '${sym.name}'.'"

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Unused effect '${red(sym.name)}'. The effect is never referenced.
         |
         |${code(sym.loc, "unused effect.")}
         |""".stripMargin
    }

    override def explain(formatter: Formatter): Option[String] = Some({
      s"""Possible fixes:
         |
         |  (1)  Use the effect.
         |  (2)  Remove the effect.
         |  (3)  Mark the effect as public.
         |  (4)  Prefix the effect name with an underscore.
         |
         |""".stripMargin
    })

    def loc: SourceLocation = sym.loc
  }

  /**
    * An error raised to indicate that the enum with the symbol `sym` is not used.
    *
    * @param sym the unused enum symbol.
    */
  case class UnusedEnumSym(sym: Symbol.EnumSym) extends RedundancyError {
    def summary: String = "Unused enum."

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Unused enum '${red(sym.name)}'. Neither the enum nor its cases are ever used.
         |
         |${code(sym.loc, "unused enum.")}
         |""".stripMargin
    }

    override def explain(formatter: Formatter): Option[String] = Some({
      s"""
         |Possible fixes:
         |
         |  (1)  Use the enum.
         |  (2)  Remove the enum.
         |  (3)  Mark the enum as public.
         |  (4)  Prefix the enum name with an underscore.
         |
         |""".stripMargin
    })

    def loc: SourceLocation = sym.loc
  }

  /**
    * An error raised to indicate that in the enum with symbol `sym` the case `tag` is not used.
    *
    * @param sym the enum symbol.
    * @param tag the unused tag.
    */
  case class UnusedEnumTag(sym: Symbol.EnumSym, tag: Symbol.CaseSym) extends RedundancyError {
    def summary: String = s"Unused case '${tag.name}'."

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Unused case '${red(tag.name)}' in enum '${cyan(sym.name)}'.
         |
         |${code(tag.loc, "unused tag.")}
         |""".stripMargin

    }

    override def explain(formatter: Formatter): Option[String] = Some({
      s"""
         |Possible fixes:
         |
         |  (1)  Use the case.
         |  (2)  Remove the case.
         |  (3)  Prefix the case with an underscore.
         |
         |""".stripMargin
    })

    def loc: SourceLocation = tag.loc
  }

  /**
    * An error raised to indicate that the struct with the symbol `sym` is not used.
    *
    * @param sym the unused struct symbol.
    */
  case class UnusedStructSym(sym: Symbol.StructSym) extends RedundancyError {
    def summary: String = "Unused struct."

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Unused struct '${red(sym.name)}'.
         |
         |${code(sym.loc, "unused struct.")}
         |""".stripMargin
    }

    override def explain(formatter: Formatter): Option[String] = Some({
      s"""
         |Possible fixes:
         |
         |  (1)  Use the struct.
         |  (2)  Remove the struct.
         |  (3)  Mark the struct as public.
         |  (4)  Prefix the struct name with an underscore.
         |
         |""".stripMargin
    })

    def loc: SourceLocation = sym.loc
  }

  /**
    * An error raised to indicate that the given formal parameter symbol `sym` is not used.
    *
    * @param sym the unused variable symbol.
    */
  case class UnusedFormalParam(sym: Symbol.VarSym) extends RedundancyError {
    def summary: String = "Unused formal parameter."

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Unused formal parameter '${red(sym.text)}'. The parameter is not used within its scope.
         |
         |${code(sym.loc, "unused formal parameter.")}
         |""".stripMargin
    }

    override def explain(formatter: Formatter): Option[String] = Some({
      s"""
         |Possible fixes:
         |
         |  (1)  Use the formal parameter.
         |  (2)  Remove the formal parameter.
         |  (3)  Prefix the formal parameter name with an underscore.
         |
         |""".stripMargin
    })

    def loc: SourceLocation = sym.loc
  }

  /**
    * An error raised to indicate that the value of an expression must be used.
    *
    * @param tpe the type of the expression.
    * @param loc the location of the expression.
    */
  case class UnusedMustUseValue(tpe: Type, loc: SourceLocation)(implicit flix: Flix) extends RedundancyError {
    def summary: String = "Unused value but its type is marked as @MustUse"

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Unused value but its type is marked as @MustUse.
         |
         |${code(loc, "unused value.")}
         |
         |The expression has type '${FormatType.formatType(tpe)}'
         |""".stripMargin
    }

    override def explain(formatter: Formatter): Option[String] = Some({
      s"""
         |Possible fixes:
         |
         |  (1)  Use the value.
         |  (2)  Explicit mark the value as unused with `discard`.
         |
         |""".stripMargin
    })
  }

  /**
    * An error raised to indicate that the given type parameter `ident` is not used.
    *
    * @param ident the unused type variable.
    */
  case class UnusedTypeParam(ident: Name.Ident, loc: SourceLocation) extends RedundancyError {
    def summary: String = "Unused type parameter."

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Unused type parameter '${red(ident.name)}'. The parameter is not referenced anywhere.
         |
         |${code(ident.loc, "unused type parameter.")}
         |""".stripMargin
    }

    override def explain(formatter: Formatter): Option[String] = Some({
      s"""
         |Possible fixes:
         |
         |  (1)  Use the type parameter.
         |  (2)  Remove type parameter.
         |  (3)  Prefix the type parameter name with an underscore.
         |
         |""".stripMargin
    })
  }

  /**
    * An error raised to indicate that the given variable symbol `sym` is not used.
    *
    * @param sym the unused variable symbol.
    */
  case class UnusedVarSym(sym: Symbol.VarSym) extends RedundancyError {
    def summary: String = "Unused local variable."

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Unused local variable '${red(sym.text)}'. The variable is not referenced within its scope.
         |
         |${code(sym.loc, "unused local variable.")}
         |""".stripMargin
    }

    override def explain(formatter: Formatter): Option[String] = Some({
      s"""
         |Possible fixes:
         |
         |  (1)  Use the local variable.
         |  (2)  Remove local variable declaration.
         |  (3)  Prefix the variable name with an underscore.
         |
         |""".stripMargin
    })

    def loc: SourceLocation = sym.loc
  }

  /**
    * An error raised to indicate that an expression is useless.
    *
    * @param tpe the type of the expression.
    * @param loc the location of the expression.
    */
  case class UselessExpression(tpe: Type, loc: SourceLocation)(implicit flix: Flix) extends RedundancyError {
    def summary: String = "Useless expression."

    def message(formatter: Formatter): String = {
      import formatter.*
      s""">> Useless expression: It has no side-effect(s) and its result is discarded.
         |
         |${code(loc, "useless expression.")}
         |
         |The expression has type '${FormatType.formatType(tpe)}'
         |""".stripMargin
    }

    override def explain(formatter: Formatter): Option[String] = Some({
      s"""
         |Possible fixes:
         |
         |  (1)  Use the result computed by the expression.
         |  (2)  Remove the expression statement.
         |  (3)  Introduce a let-binding with a wildcard name.
         |
         |""".stripMargin
    })
  }
}
