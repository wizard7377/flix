/*
 * Copyright 2020 Magnus Madsen
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
package ca.uwaterloo.flix.api.lsp

import ca.uwaterloo.flix.language.ast.TypedAst.Root
import ca.uwaterloo.flix.language.ast.shared.SymUse.TraitSymUse
import ca.uwaterloo.flix.language.ast.{SourceLocation, Symbol}

import org.eclipse.lsp4j
import org.json4s.JsonDSL.*
import org.json4s.*

/**
  * Companion object of [[LocationLink]]
  */
object LocationLink {
  /**
    * Returns a [[LocationLink]] from `originLoc` to the given target [[Symbol.AssocTypeSym]] `sym`.
    *
    * @param sym        target [[Symbol.AssocTypeSym]] that the returned [[LocationLink]] points to.
    * @param originLoc  origin [[SourceLocation]] for the [[LocationLink]].
    * @return           [[LocationLink]] from `originLoc` to the target `sym`.
    */
  def fromAssocTypeSym(sym: Symbol.AssocTypeSym, originLoc: SourceLocation): LocationLink = {
    val originSelectionRange = Range.from(originLoc)
    val targetUri = sym.loc.source.name
    val targetRange = Range.from(sym.loc)
    val targetSelectionRange = Range.from(sym.loc)
    LocationLink(originSelectionRange, targetUri, targetRange, targetSelectionRange)
  }

  /**
    * Returns a location link to the given symbol `sym`.
    */
  def fromDefSym(sym: Symbol.DefnSym, loc: SourceLocation)(implicit root: Root): LocationLink = {
    val defDecl = root.defs(sym)
    val originSelectionRange = Range.from(loc)
    val targetUri = sym.loc.source.name
    val targetRange = Range.from(sym.loc)
    val targetSelectionRange = Range.from(defDecl.sym.loc)
    LocationLink(originSelectionRange, targetUri, targetRange, targetSelectionRange)
  }

  /**
    * Returns a location link to the given symbol `sym`.
    */
  def fromSigSym(sym: Symbol.SigSym, loc: SourceLocation)(implicit root: Root): LocationLink = {
    val sigDecl = root.sigs(sym)
    val originSelectionRange = Range.from(loc)
    val targetUri = sym.loc.source.name
    val targetRange = Range.from(sym.loc)
    val targetSelectionRange = Range.from(sigDecl.sym.loc)
    LocationLink(originSelectionRange, targetUri, targetRange, targetSelectionRange)
  }

  /**
    * Returns a location link to the given symbol `sym`.
    */
  def fromEnumSym(sym: Symbol.EnumSym, loc: SourceLocation)(implicit root: Root): LocationLink = {
    val enumDecl = root.enums(sym)
    val originSelectionRange = Range.from(loc)
    val targetUri = sym.loc.source.name
    val targetRange = Range.from(enumDecl.loc)
    val targetSelectionRange = Range.from(sym.loc)
    LocationLink(originSelectionRange, targetUri, targetRange, targetSelectionRange)
  }

  /**
    * Returns a location link to the given symbol `sym`.
    */
  def fromStructSym(sym: Symbol.StructSym, loc: SourceLocation)(implicit root: Root): LocationLink = {
    val structDecl = root.structs(sym)
    val originSelectionRange = Range.from(loc)
    val targetUri = sym.loc.source.name
    val targetRange = Range.from(structDecl.loc)
    val targetSelectionRange = Range.from(sym.loc)
    LocationLink(originSelectionRange, targetUri, targetRange, targetSelectionRange)
  }

  /**
    * Returns a location link to the given symbol `sym`.
    */
  def fromCaseSym(sym: Symbol.CaseSym, loc: SourceLocation)(implicit root: Root): LocationLink = {
    val enumDecl = root.enums(sym.enumSym)
    val caseDecl = enumDecl.cases(sym)
    val originSelectionRange = Range.from(loc)
    val targetUri = sym.loc.source.name
    val targetRange = Range.from(caseDecl.loc)
    val targetSelectionRange = Range.from(caseDecl.loc)
    LocationLink(originSelectionRange, targetUri, targetRange, targetSelectionRange)
  }

  /**
    * Returns a location link to the given symbol `sym`.
    */
  def fromStructFieldSym(sym: Symbol.StructFieldSym, loc: SourceLocation)(implicit root: Root): LocationLink = {
    val structDecl = root.structs(sym.structSym)
    val fieldDecl = structDecl.fields(sym)
    val originSelectionRange = Range.from(loc)
    val targetUri = sym.loc.source.name
    val targetRange = Range.from(fieldDecl.loc)
    val targetSelectionRange = Range.from(fieldDecl.loc)
    LocationLink(originSelectionRange, targetUri, targetRange, targetSelectionRange)
  }

  /**
    * Returns a reference to the variable symbol `sym`.
    */
  def fromVarSym(sym: Symbol.VarSym, originLoc: SourceLocation): LocationLink = {
    val originSelectionRange = Range.from(originLoc)
    val targetUri = sym.loc.source.name
    val targetRange = Range.from(sym.loc)
    val targetSelectionRange = Range.from(sym.loc)
    LocationLink(originSelectionRange, targetUri, targetRange, targetSelectionRange)
  }

  /**
    * Returns a reference to the type variable symbol `sym`.
    */
  def fromTypeVarSym(sym: Symbol.KindedTypeVarSym, originLoc: SourceLocation): LocationLink = {
    val originSelectionRange = Range.from(originLoc)
    val targetUri = sym.loc.source.name
    val targetRange = Range.from(sym.loc)
    val targetSelectionRange = Range.from(sym.loc)
    LocationLink(originSelectionRange, targetUri, targetRange, targetSelectionRange)
  }

  /**
    * Returns a [[LocationLink]] from `originLoc` to the given target [[Symbol.TraitSym]] `sym`.
    *
    * @param sym        target [[Symbol.TraitSym]] that the returned [[LocationLink]] points to.
    * @param originLoc  origin [[SourceLocation]] for the [[LocationLink]].
    * @return           [[LocationLink]] from `originLoc` to the target `sym`.
    */
  def fromTraitSym(sym: Symbol.TraitSym, originLoc: SourceLocation): LocationLink = {
    val originSelectionRange = Range.from(originLoc)
    val targetUri = sym.loc.source.name
    val targetRange = Range.from(sym.loc)
    val targetSelectionRange = Range.from(sym.loc)
    LocationLink(originSelectionRange, targetUri, targetRange, targetSelectionRange)
  }

  /**
    * Returns a reference to the instance node `instance`.
    */
  def fromInstanceTraitSymUse(symUse: TraitSymUse, originLoc: SourceLocation): LocationLink = {
    val originSelectionRange = Range.from(originLoc)
    val targetUri = symUse.loc.source.name
    val targetRange = Range.from(symUse.loc)
    val targetSelectionRange = Range.from(symUse.loc)
    LocationLink(originSelectionRange, targetUri, targetRange, targetSelectionRange)
  }

  /**
    * Returns a reference to the effect symbol `sym`.
    */
  def fromEffSym(sym: Symbol.EffSym, originLoc: SourceLocation): LocationLink = {
    val originSelectionRange = Range.from(originLoc)
    val targetUri = sym.loc.source.name
    val targetRange = Range.from(sym.loc)
    val targetSelectionRange = Range.from(sym.loc)
    LocationLink(originSelectionRange, targetUri, targetRange, targetSelectionRange)
  }

  /**
    * Returns a reference to the effect operation symbol `sym`.
    */
  def fromOpSym(sym: Symbol.OpSym, originLoc: SourceLocation): LocationLink = {
    val originSelectionRange = Range.from(originLoc)
    val targetUri = sym.loc.source.name
    val targetRange = Range.from(sym.loc)
    val targetSelectionRange = Range.from(sym.loc)
    LocationLink(originSelectionRange, targetUri, targetRange, targetSelectionRange)
  }
}

/**
  * Represents a `LocationLink` in LSP.
  *
  * @param originSelectionRange Span of the origin of this link. Used as the underlined span for mouse interaction.
  *                             Defaults to the word range at the mouse position.
  * @param targetUri            The target resource identifier of this link.
  * @param targetRange          The full target range of this link. If the target for example is a symbol then target
  *                             range is the range enclosing this symbol not including leading/trailing whitespace but
  *                             everything else like comments. This information is typically used to highlight the
  *                             range in the editor.
  * @param targetSelectionRange The range that should be selected and revealed when this link is being followed,
  *                             e.g the name of a function. Must be contained by the the `targetRange`.
  *                             See also `DocumentSymbol#range`
  */
case class LocationLink(originSelectionRange: Range, targetUri: String, targetRange: Range, targetSelectionRange: Range) {
  def toJSON: JValue =
    ("originSelectionRange" -> originSelectionRange.toJSON) ~
      ("targetUri" -> targetUri) ~
      ("targetRange" -> targetRange.toJSON) ~
      ("targetSelectionRange" -> targetSelectionRange.toJSON)

  def toLsp4j: lsp4j.LocationLink = {
    val locationLink = new lsp4j.LocationLink()
    locationLink.setOriginSelectionRange(originSelectionRange.toLsp4j)
    locationLink.setTargetUri(targetUri)
    locationLink.setTargetRange(targetRange.toLsp4j)
    locationLink.setTargetSelectionRange(targetSelectionRange.toLsp4j)
    locationLink
  }
}
