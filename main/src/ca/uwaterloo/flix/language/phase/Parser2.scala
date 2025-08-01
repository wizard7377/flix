/*
 * Copyright 2024 Herluf Baggesen
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
import ca.uwaterloo.flix.language.CompilationMessage
import ca.uwaterloo.flix.language.ast.*
import ca.uwaterloo.flix.language.ast.SyntaxTree.TreeKind
import ca.uwaterloo.flix.language.ast.shared.{Source, SyntacticContext}
import ca.uwaterloo.flix.language.dbg.AstPrinter.*
import ca.uwaterloo.flix.language.errors.ParseError.*
import ca.uwaterloo.flix.language.errors.{ParseError, WeederError}
import ca.uwaterloo.flix.util.{InternalCompilerException, ParOps}

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

/**
  * A resilient LL parser. Parses a stream of tokens into a [[SyntaxTree.Tree]].
  *
  * This parser works in two steps:
  *
  *   1. First the list of tokens is traversed while emitting Open, Advance and Close events.
  *      Conceptually this is exactly the same as inserting parenthesis in a stream of tokens, only
  *      here each parenthesis is annotated with a kind.
  *      For instance:
  * {{{def main(): Int32 = 123}}}
  *      Becomes:
  * {{{(Def 'def' (Name 'main' ) '(' ')' ':' (Type 'Int32' ) '=' (Literal '123' ) )}}}
  *
  * 2. The flat list of events is automatically turned into a SyntaxTree.Tree.
  *
  * This parser is adopted from 'Resilient LL Parsing Tutorial' by Alex Kladov who works on
  * rust-analyzer. The tutorial is also a great resource for understanding this parser (and a great
  * read to boot!) [[https://matklad.github.io/2023/05/21/resilient-ll-parsing-tutorial.html]]
  */
object Parser2 {

  /** An event emitted by the parser while traversing a list of [[Token]]s. */
  private sealed trait Event

  private object Event {
    /** Opens a grouping of tokens with the [[TreeKind]] kind. */
    case class Open(kind: TreeKind) extends Event

    /** Closed the most recently opened group. */
    case object Close extends Event

    /** Advances one token adding it to the currently open group. */
    case object Advance extends Event
  }

  private class State(val tokens: Array[Token], val src: Source) {
    /** The current token being considered by the parser. */
    var position: Int = 0

    /**
      * The parser avoids endless loops via a fuel abstraction. When a look-up is made with [[nth]]
      * one unit of fuel is lost. If fuel reaches zero by this, that is a compiler error, since the
      * parser is stuck. Whenever progress is made with [[advance]] fuel is reset to its original
      * amount.
      */
    var fuel: Int = 256

    /**
      * The Parsing events emitted during parsing. Note that this is a flat collection that later
      * gets turned into a [[SyntaxTree.Tree]] by [[buildTree]].
      */
    val events: ArrayBuffer[Event] = ArrayBuffer.empty

    /**
      * Errors reside both within the produced `Tree` but are also kept here. This is done to avoid
      * crawling the tree for errors. Note that there is data-duplication, but not in the happy
      * case. An alternative could be to collect errors as part of [[buildTree]] and return them in
      * a list there.
      */
    val errors: ArrayBuffer[CompilationMessage] = ArrayBuffer.empty
  }

  /**
    * Marks point to positions in a list of tokens where an Open or Close event resides.
    *
    * This is useful because it lets the parser open a group, without knowing exactly what
    * [[TreeKind]] the group should have. For instance, we need to look past doc-comments,
    * annotations and modifiers to see what kind of declaration we are dealing with (def, enum,
    * trait...). The convention used throughout is to open a group with kind [[TreeKind.ErrorTree]]
    * and then later close it with the correct kind. This happens via the [[open]] and [[close]]
    * functions.
    *
    * Conversely, we sometimes need to open a group before the currently open one. The best example
    * is binary expressions. In '1 + 2' we first see '1' and open a group for a Literal. Then we see
    * '+' which means we want to open a Expr.Binary group that also includes the '1'. This is done
    * with [[openBefore]] which takes a [[Mark.Closed]] and inserts an Open event before it.
    * Whenever possible grammar rules return a [[Mark.Closed]] so another rule may wrap it.
    */
  private sealed trait Mark

  private object Mark {
    case class Opened(index: Int) extends Mark

    case class Closed(index: Int) extends Mark
  }

  def run(tokens0: Map[Source, Array[Token]], oldRoot: SyntaxTree.Root, changeSet: ChangeSet)(implicit flix: Flix): (SyntaxTree.Root, List[CompilationMessage]) = flix.phaseNew("Parser2") {
    // Compute the stale and fresh sources.
    val (stale, fresh) = changeSet.partition(tokens0, oldRoot.units)

    // Sort the stale inputs by size to increase throughput (i.e. to start work early on the biggest tasks).
    val staleByDecreasingSize = stale.toList.sortBy(p => -p._2.length)

    // Parse each stale source in parallel and join them into a WeededAst.Root.
    val (refreshed, errors) = ParOps.parMap(staleByDecreasingSize) {
      case (src, tokens) =>
        val (tree, errors) = parse(src, tokens)
        (src -> tree, errors)
    }.unzip

    // Join refreshed syntax trees with the already fresh ones.
    val result = SyntaxTree.Root(refreshed.toMap ++ fresh, tokens0)
    (result, errors.flatten.toList)
  }

  private def parse(src: Source, tokens: Array[Token]): (SyntaxTree.Tree, List[CompilationMessage]) = {
    implicit val s: State = new State(tokens, src)
    // Call the top-most grammar rule to gather all events into state.
    root()
    // Build the syntax tree using events in state.
    val tree = buildTree()
    // Return with errors as soft failures to run subsequent phases for more validations.
    (tree, s.errors.toList)
  }

  private def buildTree()(implicit s: State): SyntaxTree.Tree = {
    val tokens = s.tokens.iterator.buffered
    // We are using lists as stacks here (Scala does have a 'Stack', but it is backed by an
    // ArrayDeque). Lists are fastest here though since they have constant time prepend (+:) and
    // tail implementations. We use prepend on Event.Open and stack.tail on Event.Close.
    var stack: List[SyntaxTree.Tree] = List.empty
    var locationStack: List[Token] = List.empty

    // Pop the last event, which must be a Close, to ensure that the stack is not empty when
    // handling event below.
    val lastEvent = s.events.last
    s.events.dropRightInPlace(1)
    assert(lastEvent match {
      case Event.Close => true
      case _ => false
    })

    // Make a synthetic token to begin with, to make the SourceLocations generated below be correct.
    val b = SourcePosition.firstPosition(s.src)
    val e = SourcePosition.firstPosition(s.src)
    var lastAdvance = Token(TokenKind.Eof, s.src, 0, 0, b, e)
    for (event <- s.events) {
      event match {
        case Event.Open(kind) =>
          locationStack = tokens.head +: locationStack
          stack = SyntaxTree.Tree(kind, Array.empty, SourceLocation.Unknown) +: stack

        case Event.Close =>
          val child = stack.head
          val openToken = locationStack.head
          stack.head.loc = if (stack.head.children.length == 0)
            // If the subtree has no children, give it a zero length position just after the last
            // token.
            SourceLocation(
              isReal = true,
              lastAdvance.sp2,
              lastAdvance.sp2
            )
          else
            // Otherwise the source location can span from the first to the last token in the
            // subtree.
            SourceLocation(
              isReal = true,
              openToken.sp1,
              lastAdvance.sp2
            )
          locationStack = locationStack.tail
          stack = stack.tail
          stack.head.children = stack.head.children :+ child

        case Event.Advance =>
          val token = tokens.next()
          lastAdvance = token
          stack.head.children = stack.head.children :+ token
      }
    }

    // Set source location of the root.
    stack.last.loc = SourceLocation(
      isReal = true,
      SourcePosition.firstPosition(s.src),
      tokens.head.sp2
    )

    // The stack should now contain a single Source tree, and there should only be an <eof> token
    // left.
    assert(stack.length == 1)
    assert(tokens.next().kind == TokenKind.Eof)
    stack.head
  }

  /** Get first non-comment previous position of the parser as a [[SourceLocation]]. */
  private def previousSourceLocation()(implicit s: State): SourceLocation = {
    // TODO: It might make sense to seek the first non-comment position.
    val token = s.tokens((s.position - 1).max(0))
    SourceLocation(isReal = true, token.sp1, token.sp2)
  }

  /** Get current position of the parser as a [[SourceLocation]]. */
  private def currentSourceLocation()(implicit s: State): SourceLocation = {
    val token = s.tokens(s.position)
    SourceLocation(isReal = true, token.sp1, token.sp2)
  }

  /**
    * Opens a group with kind [[TreeKind.UnclosedMark]].
    * Each call to [[open]] must have a pairing call to [[close]]. This is asserted in [[buildTree]].
    * [[open]] consumes comments into the opened group.
    */
  private def open(consumeDocComments: Boolean = true)(implicit s: State): Mark.Opened = {
    val mark = Mark.Opened(s.events.length)
    s.events.append(Event.Open(TreeKind.UnclosedMark))
    // Consume any comments just before opening a new mark.
    comments(consumeDocComments = consumeDocComments)
    mark
  }

  /**
    * Closes a group and marks it with `kind`. [[close]] consumes comments into the group before
    * closing.
    */
  private def close(mark: Mark.Opened, kind: TreeKind)(implicit s: State): Mark.Closed = {
    s.events(mark.index) = Event.Open(kind)
    // Consume any comments just before closing a mark.
    comments()
    s.events.append(Event.Close)
    Mark.Closed(mark.index)
  }

  /**
    * Opens a group before another one.
    *
    * This is useful when we want to wrap one or more groups in a parent group.
    *
    * For instance:
    * (Literal '1') '+' (Literal '2')
    *
    * Into:
    * (Expr.Binary (Literal '1') '+' (Literal '2'))
    */
  private def openBefore(before: Mark.Closed)(implicit s: State): Mark.Opened = {
    val mark = Mark.Opened(before.index)
    s.events.insert(before.index, Event.Open(TreeKind.UnclosedMark))
    mark
  }

  /** Advances the parser one token and refuel the parser. */
  private def advance()(implicit s: State): Unit = {
    if (eof()) {
      return
    }
    s.fuel = 256
    s.events.append(Event.Advance)
    s.position += 1
  }

  private def closeWithError(mark: Mark.Opened, error: CompilationMessage, token: Option[TokenKind] = None)(implicit s: State): Mark.Closed = {
    token.getOrElse(nth(0)) match {
      // Avoid double reporting lexer errors.
      case TokenKind.Err(_) =>
      case _ => s.errors.append(error)
    }
    close(mark, TreeKind.ErrorTree(error))
  }

  /** Wrap the next token in an error. */
  private def advanceWithError(error: CompilationMessage, mark: Option[Mark.Opened] = None)(implicit s: State): Mark.Closed = {
    val m = mark.getOrElse(open())
    nth(0) match {
      // Avoid double reporting lexer errors.
      case TokenKind.Err(_) =>
      case _ => s.errors.append(error)
    }
    advance()
    close(m, TreeKind.ErrorTree(error))
  }

  /** Check if the parser is at the end-of-file. */
  private def eof()(implicit s: State): Boolean = {
    s.position == s.tokens.length - 1
  }

  /**
    * Look-ahead `lookahead` tokens.
    *
    * Consume one fuel and throws [[InternalCompilerException]] if the parser is out of fuel but
    * does not consume fuel if parser has hit end-of-file. This lets the parser return whatever
    * errors were produced from a deep call-stack without running out of fuel.
    */
  private def nth(lookahead: Int)(implicit s: State): TokenKind = {
    if (s.fuel == 0) {
      throw InternalCompilerException(s"Parser is stuck", currentSourceLocation())
    }

    if (s.position + lookahead >= s.tokens.length - 1) {
      TokenKind.Eof
    } else {
      s.fuel -= 1
      s.tokens(s.position + lookahead).kind
    }
  }

  /** Checks if the parser is at a token of a specific `kind`. */
  private def at(kind: TokenKind)(implicit s: State): Boolean = {
    nth(0) == kind
  }

  /** Checks if the parser is at a token of kind in `kinds`. */
  private def atAny(kinds: Set[TokenKind])(implicit s: State): Boolean = {
    kinds.contains(nth(0))
  }

  /**
    * Checks if the parser is at a token of kind in `kinds` and returns the found token if possible.
    */
  private def atAnyOpt(kinds: Set[TokenKind])(implicit s: State): Option[TokenKind] = {
    val token = nth(0)
    Some(token).filter(kinds.contains)
  }

  /** Checks if the parser is at a token of a specific `kind` and advances past it if it is. */
  private def eat(kind: TokenKind)(implicit s: State): Boolean = {
    if (at(kind)) {
      advance()
      true
    } else {
      false
    }
  }

  /** Checks if the parser is at a token of kind in `kinds` and advances past it if it is. */
  private def eatAny(kinds: Set[TokenKind])(implicit s: State): Boolean = {
    if (atAny(kinds)) {
      advance()
      true
    } else {
      false
    }
  }

  /**
    * Checks if the parser is at a token of kind in `kinds` and advances past it if it is. Returns
    * the found token if one was found
    */
  private def eatAnyOpt(kinds: Set[TokenKind])(implicit s: State): Option[TokenKind] = {
    atAnyOpt(kinds) match {
      case some@Some(_) =>
        advance()
        some
      case None =>
        None
    }
  }

  /**
    * Advance past current token if it is of kind `kind`. Otherwise wrap it in an error.
    *
    * [[expect]] does not consume comments. That means that consecutive calls to [[expect]] work in
    * an atomic manner.
    *
    * expect(TokenKind.KeywordIf)
    * expect(TokenKind.ParenL)
    *
    * Means "if (" with no comment between "if" and "(".
    */
  private def expect(kind: TokenKind, hint: Option[String] = None)(implicit sctx: SyntacticContext, s: State): Unit = {
    if (eat(kind)) {
      return
    }

    val mark = open()
    val error = nth(0) match {
      case TokenKind.CommentLine => MisplacedComments(sctx, currentSourceLocation())
      case TokenKind.CommentBlock => MisplacedComments(sctx, currentSourceLocation())
      case TokenKind.CommentDoc => MisplacedDocComments(sctx, currentSourceLocation())
      case at => UnexpectedToken(expected = NamedTokenSet.FromKinds(Set(kind)), actual = Some(at), sctx, hint = hint, loc = currentSourceLocation())
    }
    closeWithError(mark, error)
  }

  /** Advance past current token if it is of kind in `kinds`. Otherwise wrap it in an error. */
  private def expectAny(kinds: Set[TokenKind], hint: Option[String] = None)(implicit sctx: SyntacticContext, s: State): Unit = {
    if (eatAny(kinds)) {
      return
    }
    val mark = open()
    val error = nth(0) match {
      case TokenKind.CommentLine => MisplacedComments(sctx, currentSourceLocation())
      case TokenKind.CommentBlock => MisplacedComments(sctx, currentSourceLocation())
      case TokenKind.CommentDoc => MisplacedDocComments(sctx, currentSourceLocation())
      case at => UnexpectedToken(expected = NamedTokenSet.FromKinds(kinds), actual = Some(at), sctx, hint = hint, loc = currentSourceLocation())
    }
    closeWithError(mark, error)
  }

  /**
    * Advance past current token if it is of kind in `kinds`. Otherwise wrap it in an error.
    * Returns the found token if applicable.
    */
  private def expectAnyOpt(kinds: Set[TokenKind], hint: Option[String] = None)(implicit sctx: SyntacticContext, s: State): Option[TokenKind] = {
    eatAnyOpt(kinds) match {
      case some@Some(_) => return some
      case None => ()
    }
    val mark = open()
    val error = nth(0) match {
      case TokenKind.CommentLine => MisplacedComments(sctx, currentSourceLocation())
      case TokenKind.CommentBlock => MisplacedComments(sctx, currentSourceLocation())
      case TokenKind.CommentDoc => MisplacedDocComments(sctx, currentSourceLocation())
      case at => UnexpectedToken(expected = NamedTokenSet.FromKinds(kinds), actual = Some(at), sctx, hint = hint, loc = currentSourceLocation())
    }
    closeWithError(mark, error)
    None
  }

  /**
    * Checks if a token of kind `needle` can be found before any token of a kind in `before`.
    *
    * This is useful for detecting what grammar rule to use, when language constructs share a prefix.
    * For instance, when sitting on a '{' it's not clear if a block or a record is to come.
    * But if we can find a '|' before either '{' or '}' we know it is a record.
    */
  private def findBefore(needle: TokenKind, before: Array[TokenKind])(implicit s: State): Boolean = {
    var lookahead = 1
    while (!eof()) {
      nth(lookahead) match {
        case t if t == needle => return true
        case t if before.contains(t) => return false
        case TokenKind.Eof => return false
        case _ => lookahead += 1
      }
    }
    false
  }

  /** Separation permissions between a list of items. */
  sealed trait Separation

  private object Separation {
    /**
      * Separator is required and will report an error if missing.
      *
      * @param separator     TokenKind to be used as separator.
      * @param allowTrailing Whether to allow a trailing separator token.
      */
    case class Required(separator: TokenKind, allowTrailing: Boolean = false) extends Separation

    /**
      * Separator is optional and will *not* report an error if missing.
      *
      * @param separator     TokenKind to be used as separator.
      * @param allowTrailing Whether to allow a trailing separator token.
      */
    case class Optional(separator: TokenKind, allowTrailing: Boolean = false) extends Separation

    /** Separator is disallowed. */
    case object None extends Separation
  }

  /**
    * Parses a number of items surrounded by delimiters and separated by some token.
    *
    * Examples of language features that use [[zeroOrMore]]:
    *   - Tuples "(1, 2, 3)".
    *   - Records "{ -y, +z = 3 | r }" <- Note the '| r' part. That can be handled by
    *     `optionallyWith`.
    *   - ParFieldFragments "par (x <- e1; y <- e2; z <- e3) yield ...".
    *
    * and many many more...
    *
    * @param namedTokenSet  The named token set to be used in an error message. ie. "Expected
    *                       $namedTokenSet before xyz".
    * @param getItem        Function for parsing a single item.
    * @param checkForItem   Function used to check if the next token indicates an item. getItem is
    *                       only called when this returns true.
    * @param breakWhen      Function for deciding if an unexpected token is in the recover set of
    *                       the current context. If it is then parsing of items stops.
    * @param separation     Separation mode. Either required, optional or none.
    * @param delimiterL     The left delimiter.
    * @param delimiterR     The right delimiter.
    * @param optionallyWith Used to parse a single rule before delimiterR but after all items if a
    *                       specific token is found. For instance in a record operation
    *                       "{ +x, -y, z = 3 | w }" we can parse the "| w" part with
    *                       `optionallyWith = Some((TokenKind.Bar, () => expression()))`
    * @return The number of items successfully parsed.
    */
  private def zeroOrMore(
                          namedTokenSet: NamedTokenSet,
                          getItem: () => Mark.Closed,
                          checkForItem: TokenKind => Boolean,
                          breakWhen: TokenKind => Boolean,
                          separation: Separation = Separation.Required(TokenKind.Comma),
                          delimiterL: TokenKind = TokenKind.ParenL,
                          delimiterR: TokenKind = TokenKind.ParenR,
                          optionallyWith: Option[(TokenKind, () => Unit)] = None
                        )(implicit sctx: SyntacticContext, s: State): Int = {
    def atEnd(): Boolean = at(delimiterR) || optionallyWith.exists { case (indicator, _) => at(indicator) }

    if (!at(delimiterL)) {
      return 0
    }
    expect(delimiterL)
    var continue = true
    var numItems = 0
    while (continue && !atEnd() && !eof()) {
      comments()
      val kind = nth(0)
      if (checkForItem(kind)) {
        getItem()
        numItems += 1
        if (!atEnd()) {
          // Check for separator if needed.
          separation match {
            case Separation.Required(separator, _) => expect(separator)
            case Separation.Optional(separator, _) => eat(separator)
            case Separation.None =>
          }
          // Check for trailing separator if needed.
          if (atEnd()) {
            separation match {
              case Separation.Required(separator, false) => closeWithError(open(), TrailingSeparator(separator, sctx, previousSourceLocation()))
              case Separation.Optional(separator, false) => closeWithError(open(), TrailingSeparator(separator, sctx, previousSourceLocation()))
              case _ =>
            }
          }
        }
      } else {
        // We are not at an item (checkForItem returned false).
        // Break out of the loop if we hit one of the tokens in the recover set.
        if (breakWhen(kind)) {
          continue = false
        } else {
          // Otherwise eat one token and continue parsing next item.
          val error = UnexpectedToken(expected = namedTokenSet, actual = Some(nth(0)), sctx, loc = currentSourceLocation())
          advanceWithError(error)
        }
      }
      // Consume any comments trailing an item.
      // This is needed because the comment might be just before delimiterR obscuring the atEnd check.
      comments()
    }
    optionallyWith match {
      case Some((indicator, rule)) => if (eat(indicator)) {
        rule()
      }
      case None =>
    }
    expect(delimiterR)
    numItems
  }

  /**
    * Parses one ore more items surrounded by delimiters and separated by some token.
    *
    * Works by forwarding parameters to [[zeroOrMore]] and then checking that there was at least one
    * item parsed. Otherwise an error is produced.
    *
    * See [[zeroOrMore]] for documentation of parameters.
    *
    * @return An optional parse error. If one or more item is found, None is returned.
    */
  private def oneOrMore(
                         namedTokenSet: NamedTokenSet,
                         getItem: () => Mark.Closed,
                         checkForItem: TokenKind => Boolean,
                         breakWhen: TokenKind => Boolean,
                         separation: Separation = Separation.Required(TokenKind.Comma),
                         delimiterL: TokenKind = TokenKind.ParenL,
                         delimiterR: TokenKind = TokenKind.ParenR,
                         optionallyWith: Option[(TokenKind, () => Unit)] = None
                       )(implicit sctx: SyntacticContext, s: State): Option[ParseError] = {
    val locBefore = currentSourceLocation()
    val itemCount = zeroOrMore(namedTokenSet, getItem, checkForItem, breakWhen, separation, delimiterL, delimiterR, optionallyWith)
    val locAfter = previousSourceLocation()
    if (itemCount < 1) {
      val loc = SourceLocation(isReal = true, locBefore.sp1, locAfter.sp1)
      Some(NeedAtleastOne(namedTokenSet, sctx, loc = loc))
    } else {
      None
    }
  }

  /**
    * Groups of [[TokenKind]]s that make of the different kinds of names in Flix.
    * So for instance NAME_PARAMETER is all the kinds of tokens that may occur as a parameter identifier.
    *
    * Use these together with [[nameUnqualified]] and [[nameAllowQualified]].
    */
  private val NAME_DEFINITION: Set[TokenKind] = Set(TokenKind.NameLowerCase, TokenKind.NameUpperCase, TokenKind.NameMath, TokenKind.NameGreek, TokenKind.UserDefinedOperator)
  private val NAME_PARAMETER: Set[TokenKind] = Set(TokenKind.NameLowerCase, TokenKind.NameMath, TokenKind.NameGreek, TokenKind.Underscore)
  private val NAME_VARIABLE: Set[TokenKind] = Set(TokenKind.NameLowerCase, TokenKind.NameMath, TokenKind.NameGreek, TokenKind.Underscore)
  private val NAME_JAVA: Set[TokenKind] = Set(TokenKind.NameLowerCase, TokenKind.NameUpperCase)
  private val NAME_QNAME: Set[TokenKind] = Set(TokenKind.NameLowerCase, TokenKind.NameUpperCase)
  private val NAME_USE: Set[TokenKind] = Set(TokenKind.NameLowerCase, TokenKind.NameUpperCase, TokenKind.NameMath, TokenKind.NameGreek, TokenKind.UserDefinedOperator)
  private val NAME_FIELD: Set[TokenKind] = Set(TokenKind.NameLowerCase)
  // TODO: Static is used as a type in Prelude.flix. Static is also an expression.
  //       refactor When Static is used as a region "@ Static" to "@ static" since lowercase is
  //       a keyword.
  private val NAME_TYPE: Set[TokenKind] = Set(TokenKind.NameUpperCase, TokenKind.KeywordStaticUppercase)
  private val NAME_KIND: Set[TokenKind] = Set(TokenKind.NameUpperCase)
  private val NAME_EFFECT: Set[TokenKind] = Set(TokenKind.NameUpperCase)
  private val NAME_MODULE: Set[TokenKind] = Set(TokenKind.NameUpperCase)
  private val NAME_TAG: Set[TokenKind] = Set(TokenKind.NameUpperCase)
  private val NAME_PREDICATE: Set[TokenKind] = Set(TokenKind.NameUpperCase)

  /** Consumes a token if kind is in `kinds` and wraps it in a [[TreeKind.Ident]]. */
  private def nameUnqualified(kinds: Set[TokenKind])(implicit sctx: SyntacticContext, s: State): Mark.Closed = {
    val mark = open(consumeDocComments = false)

    // Check if we are at a keyword and emit nice error if so.
    val current = nth(0)
    if (current.isKeyword) {
      // If the keyword can start a declaration it's best to leave it be.
      if (!current.isFirstDecl) {
        advance()
      }
      return closeWithError(mark, UnexpectedToken(
        NamedTokenSet.FromKinds(kinds),
        actual = Some(current),
        sctx = sctx,
        hint = Some(s"${current.display} is a keyword."),
        loc = previousSourceLocation()
      ))
    }

    expectAny(kinds)
    close(mark, TreeKind.Ident)
  }

  /**
    * Consumes a token if kind is in `kinds` and also consume subsequent dot-separated tokens with
    * kind in `kinds`.
    *
    * @param tail If any kind found is in `tail` then no further dot separated tokens will be
    *             consumed.
    */
  private def nameAllowQualified(kinds: Set[TokenKind], tail: Set[TokenKind] = Set(TokenKind.NameLowerCase))(implicit sctx: SyntacticContext, s: State): Mark.Closed = {
    val mark = open(consumeDocComments = false)

    // Check if we are at a keyword and emit nice error if so.
    val current = nth(0)
    if (current.isKeyword) {
      // If the keyword can start a declaration it's best to leave it be.
      if (!current.isFirstDecl) {
        advance()
      }
      return closeWithError(mark, UnexpectedToken(
        NamedTokenSet.FromKinds(kinds),
        actual = Some(current),
        sctx = sctx,
        hint = Some(s"${current.display} is a keyword."),
        loc = previousSourceLocation()
      ))
    }

    val foundToken = expectAnyOpt(kinds)
    val first = close(mark, TreeKind.Ident)

    var isTail: Boolean = foundToken.exists(tail.contains)
    var continue = true
    while (continue && !isTail && !eof()) {
      nth(0) match {
        case TokenKind.Dot =>
          if (!kinds.contains(nth(1))) {
            // Trailing dot: stop parsing the qualified name.
            val error = UnexpectedToken(
              expected = NamedTokenSet.FromKinds(kinds),
              actual = None,
              sctx = sctx,
              hint = Some("expect ident after '.'."),
              loc = currentSourceLocation()
            )
            val mark = open()
            advance()
            close(mark, TreeKind.TrailingDot)
            s.errors.append(error)
            continue = false
          } else {
            advance() // Eat the dot
            val mark = open()
            val found = expectAnyOpt(kinds)
            found.foreach(t => isTail = tail.contains(t))
            close(mark, TreeKind.Ident)
          }
        case TokenKind.DotWhiteSpace =>
          // Trailing dot, stop parsing the qualified name.
          val error = UnexpectedToken(
            expected = NamedTokenSet.FromKinds(kinds),
            actual = None,
            sctx = sctx,
            hint = Some("expect ident after '.'."),
            loc = currentSourceLocation()
          )
          val mark = open()
          advance()
          close(mark, TreeKind.TrailingDot)
          s.errors.append(error)
          continue = false
        case _ => continue = false
      }
    }

    close(openBefore(first), TreeKind.QName)
  }

  /**
    * Consumes comments.
    *
    * @param consumeDocComments If true, allow doc-comments to be parsed as a regular comment.
    */
  private def comments(consumeDocComments: Boolean = false)(implicit s: State): Unit = {
    implicit val sctx: SyntacticContext = SyntacticContext.Decl.Module

    // This function does not use nth on purpose, to avoid consuming fuel.
    // Both open and close call this function, so using nth would consume all the parsers fuel on
    // comments.
    def atComment(): Boolean = {
      val current = if (s.position >= s.tokens.length - 1) {
        TokenKind.Eof
      } else {
        s.tokens(s.position).kind
      }
      if (consumeDocComments) current.isComment else current.isCommentNonDoc
    }

    if (atComment()) {
      val mark = Mark.Opened(s.events.length)
      s.events.append(Event.Open(TreeKind.UnclosedMark))
      // This loop will also consume doc-comments that are preceded or surrounded by either line or
      // block comments.
      while (atComment() && !eof()) {
        advance()
      }
      // Check for a trailing doc-comment that is not followed by a declaration.
      val isDanglingDoc = consumeDocComments && nth(-1) == TokenKind.CommentDoc && !nth(0).isDocumentable
      if (isDanglingDoc) {
        val errMark = open()
        closeWithError(errMark, MisplacedDocComments(sctx, previousSourceLocation()))
      }
      close(mark, TreeKind.CommentList)
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////
  /// GRAMMAR                                                                                     //
  //////////////////////////////////////////////////////////////////////////////////////////////////
  private def root()(implicit s: State): Unit = {
    val mark = open(consumeDocComments = false)
    usesOrImports()
    while (!eof()) {
      Decl.declaration()
    }
    close(mark, TreeKind.Root)
  }

  private def usesOrImports()(implicit s: State): Mark.Closed = {
    val mark = open(consumeDocComments = false)
    var continue = true
    while (continue && !eof()) {
      nth(0) match {
        case TokenKind.KeywordUse =>
          use()
          eat(TokenKind.Semi)
        case TokenKind.KeywordImport =>
          iimport()
          eat(TokenKind.Semi)
        case _ => continue = false
      }
    }
    close(mark, TreeKind.UsesOrImports.UseOrImportList)
  }

  private def use()(implicit s: State): Mark.Closed = {
    implicit val sctx: SyntacticContext = SyntacticContext.Unknown
    assert(at(TokenKind.KeywordUse))
    val mark = open()
    expect(TokenKind.KeywordUse)
    nameAllowQualified(NAME_USE)
    // Handle use many case.
    if (at(TokenKind.DotCurlyL)) {
      val mark = open()
      zeroOrMore(
        namedTokenSet = NamedTokenSet.Name,
        getItem = () => aliasedName(NAME_USE),
        checkForItem = NAME_USE.contains,
        breakWhen = _.isRecoverUseOrImport,
        delimiterL = TokenKind.DotCurlyL,
        delimiterR = TokenKind.CurlyR,
      )
      close(mark, TreeKind.UsesOrImports.UseMany)
    }
    close(mark, TreeKind.UsesOrImports.Use)
  }

  private def iimport()(implicit s: State): Mark.Closed = {
    implicit val sctx: SyntacticContext = SyntacticContext.Unknown
    assert(at(TokenKind.KeywordImport))
    val mark = open()
    expect(TokenKind.KeywordImport)
    nameAllowQualified(NAME_JAVA, tail = Set())
    // Handle import many case.
    if (at(TokenKind.DotCurlyL)) {
      val mark = open()
      oneOrMore(
        namedTokenSet = NamedTokenSet.Name,
        getItem = () => aliasedName(NAME_JAVA),
        checkForItem = NAME_JAVA.contains,
        breakWhen = _.isRecoverUseOrImport,
        delimiterL = TokenKind.DotCurlyL,
        delimiterR = TokenKind.CurlyR,
      )
      close(mark, TreeKind.UsesOrImports.ImportMany)
    }
    close(mark, TreeKind.UsesOrImports.Import)
  }

  private def aliasedName(names: Set[TokenKind])(implicit sctx: SyntacticContext, s: State): Mark.Closed = {
    var lhs = nameUnqualified(names)
    if (eat(TokenKind.ArrowThickR)) {
      nameUnqualified(names)
      lhs = close(openBefore(lhs), TreeKind.UsesOrImports.Alias)
    }
    lhs
  }

  private object Decl {
    def declaration(nestingLevel: Int = 0)(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Decl.Module
      val mark = open(consumeDocComments = false)
      docComment()
      // Handle modules.
      if (at(TokenKind.KeywordMod)) {
        return moduleDecl(mark, nestingLevel)
      }
      // Handle declarations
      annotations()
      modifiers()
      // If a new declaration is added to this then add it to FIRST_DECL too.
      nth(0) match {
        case TokenKind.KeywordTrait => traitDecl(mark)
        case TokenKind.KeywordInstance => instanceDecl(mark)
        case TokenKind.KeywordDef => definitionDecl(mark)
        case TokenKind.KeywordEnum | TokenKind.KeywordRestrictable => enumerationDecl(mark)
        case TokenKind.KeywordStruct => structDecl(mark)
        case TokenKind.KeywordType => typeAliasDecl(mark)
        case TokenKind.KeywordEff => effectDecl(mark)
        case TokenKind.Eof => close(mark, TreeKind.CommentList) // Last tokens in the file were comments.
        case at =>
          val loc = currentSourceLocation()
          val error = UnexpectedToken(expected = NamedTokenSet.Declaration, actual = Some(at), sctx, loc = loc)
          if (nestingLevel == 0) {
            // If we are at top-level (nestingLevel == 0) skip ahead until we hit another declaration.
            // If we are in a module (nestingLevel > 0) we let the module rule handle recovery.
            while (!nth(0).isRecoverDecl && !eof()) {
              advance()
            }
          }
          closeWithError(mark, error, Some(at))
      }
    }

    private def moduleDecl(mark: Mark.Opened, nestingLevel: Int = 0)(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Decl.Module
      assert(at(TokenKind.KeywordMod))
      expect(TokenKind.KeywordMod)
      nameAllowQualified(NAME_MODULE)
      expect(TokenKind.CurlyL)
      usesOrImports()
      var continue = true
      while (continue && !eof()) {
        nth(0) match {
          case t if t.isFirstDecl => declaration(nestingLevel + 1)
          case TokenKind.CurlyR => continue = false
          case at =>
            val markErr = open()
            val loc = currentSourceLocation()
            val error = UnexpectedToken(expected = NamedTokenSet.Declaration, actual = Some(at), sctx, loc = loc)
            // Skip ahead until we find another declaration or a '}', signifying the end of the module.
            while (!nth(0).isRecoverMod && !eof()) {
              advance()
            }
            closeWithError(markErr, error)
        }
      }
      expect(TokenKind.CurlyR)
      close(mark, TreeKind.Decl.Module)
    }

    private def traitDecl(mark: Mark.Opened)(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Decl.Trait
      assert(at(TokenKind.KeywordTrait))
      expect(TokenKind.KeywordTrait)
      nameUnqualified(NAME_DEFINITION)
      Type.parameters()
      if (at(TokenKind.KeywordWith)) {
        Type.constraints()
      }
      if (at(TokenKind.CurlyL)) {
        expect(TokenKind.CurlyL)
        var continue = true
        while (continue && !eof()) {
          val docMark = docComment()
          annotations()
          modifiers()
          nth(0) match {
            case TokenKind.CurlyR => continue = false
            case TokenKind.KeywordLaw => lawDecl(openBefore(docMark))
            case TokenKind.KeywordDef => signatureDecl(openBefore(docMark))
            case TokenKind.KeywordType => associatedTypeSigDecl(openBefore(docMark))
            case at =>
              val errMark = open()
              val loc = currentSourceLocation()
              // Skip ahead until we hit another declaration or a CurlyR.
              while (!nth(0).isFirstTrait && !eat(TokenKind.CurlyR) && !eof()) {
                advance()
              }
              val error = UnexpectedToken(expected = NamedTokenSet.FromKinds(Set(TokenKind.KeywordType, TokenKind.KeywordDef, TokenKind.KeywordLaw)), actual = Some(at), sctx, loc = loc)
              closeWithError(errMark, error, Some(at))
          }
        }
        expect(TokenKind.CurlyR)
      }
      close(mark, TreeKind.Decl.Trait)
    }

    private def instanceDecl(mark: Mark.Opened)(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Decl.Instance
      assert(at(TokenKind.KeywordInstance))
      expect(TokenKind.KeywordInstance)
      nameAllowQualified(NAME_DEFINITION)
      if (!eat(TokenKind.BracketL)) {
        // Produce an error for missing type parameter.
        expect(TokenKind.BracketL, hint = Some("instances must have a type parameter."))
      } else {
        Type.ttype()
        expect(TokenKind.BracketR)
      }
      if (at(TokenKind.KeywordWith)) {
        Type.constraints()
      }
      if (at(TokenKind.KeywordWhere)) {
        equalityConstraints()
      }
      if (at(TokenKind.CurlyL)) {
        expect(TokenKind.CurlyL)
        var continue = true
        while (continue && !eof()) {
          val docMark = docComment()
          annotations()
          modifiers()
          nth(0) match {
            case TokenKind.CurlyR => continue = false
            case TokenKind.KeywordDef => definitionDecl(openBefore(docMark))
            case TokenKind.KeywordRedef => definitionDecl(openBefore(docMark), declKind = TokenKind.KeywordRedef)
            case TokenKind.KeywordType => associatedTypeDefDecl(openBefore(docMark))
            case at =>
              val errMark = open()
              val loc = currentSourceLocation()
              // Skip ahead until we hit another declaration or a CurlyR.
              while (!nth(0).isFirstInstance && !eat(TokenKind.CurlyR) && !eof()) {
                advance()
              }
              val error = UnexpectedToken(expected = NamedTokenSet.FromKinds(Set(TokenKind.KeywordType, TokenKind.KeywordDef)), actual = Some(at), sctx, loc = loc)
              closeWithError(errMark, error, Some(at))
          }
        }
        expect(TokenKind.CurlyR)
      }
      close(mark, TreeKind.Decl.Instance)
    }

    private def signatureDecl(mark: Mark.Opened)(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Decl.Module
      assert(at(TokenKind.KeywordDef))
      expect(TokenKind.KeywordDef)
      nameUnqualified(NAME_DEFINITION)
      if (at(TokenKind.BracketL)) {
        Type.parameters()
      }
      parameters()
      expect(TokenKind.Colon)
      Type.typeAndEffect()

      if (at(TokenKind.KeywordWith)) {
        Type.constraints()
      }
      if (at(TokenKind.KeywordWhere)) {
        equalityConstraints()
      }
      if (eat(TokenKind.Equal)) {
        Expr.statement()
      }
      close(mark, TreeKind.Decl.Signature)
    }

    private def definitionDecl(mark: Mark.Opened, declKind: TokenKind = TokenKind.KeywordDef)(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Decl.Module
      assert(at(declKind))
      expect(declKind)
      nameUnqualified(NAME_DEFINITION)
      if (at(TokenKind.BracketL)) {
        Type.parameters()
      }
      parameters()
      expect(TokenKind.Colon)
      Type.typeAndEffect()
      if (at(TokenKind.KeywordWith)) {
        Type.constraints()
      }
      if (at(TokenKind.KeywordWhere)) {
        equalityConstraints()
      }

      // We want to only parse an expression if we see an equal sign to avoid consuming following
      // definitions as LocalDef.
      //
      // In this example we want to avoid consuming 'main' as a nested function, even though 'def'
      // signifies a LocalDef:
      //
      // def f(): Unit // <- no equal sign
      // def main(): Unit = ()
      if (eat(TokenKind.Equal)) {
        Expr.statement()
      } else {
        expect(TokenKind.Equal) // Produce an error for missing '='.
      }

      val treeKind = if (declKind == TokenKind.KeywordRedef) TreeKind.Decl.Redef else TreeKind.Decl.Def
      close(mark, treeKind)
    }

    private def lawDecl(mark: Mark.Opened)(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Decl.Module
      assert(at(TokenKind.KeywordLaw))
      expect(TokenKind.KeywordLaw)
      nameUnqualified(NAME_DEFINITION)
      expect(TokenKind.Colon)
      expect(TokenKind.KeywordForall)
      if (at(TokenKind.BracketL)) {
        Type.parameters()
      }
      if (at(TokenKind.ParenL)) {
        parameters()
      }
      if (at(TokenKind.KeywordWith)) {
        Type.constraints()
      }
      if (at(TokenKind.KeywordWhere)) {
        equalityConstraints()
      }
      Expr.expression()
      close(mark, TreeKind.Decl.Law)
    }

    private def enumerationDecl(mark: Mark.Opened)(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Decl.Enum
      assert(atAny(Set(TokenKind.KeywordRestrictable, TokenKind.KeywordEnum)))
      val isRestrictable = eat(TokenKind.KeywordRestrictable)
      expect(TokenKind.KeywordEnum)
      val nameLoc = currentSourceLocation()
      nameUnqualified(NAME_TYPE)
      if (isRestrictable) {
        expect(TokenKind.BracketL)
        val markParam = open()
        nameUnqualified(NAME_VARIABLE)
        close(markParam, TreeKind.Parameter)
        expect(TokenKind.BracketR)
      }
      if (at(TokenKind.BracketL)) {
        Type.parameters()
      }
      // Singleton short-hand.
      val isShorthand = at(TokenKind.ParenL)
      if (isShorthand) {
        val markType = open()
        val mark = open()
        oneOrMore(
          namedTokenSet = NamedTokenSet.Type,
          getItem = () => Type.ttype(),
          checkForItem = _.isFirstType,
          breakWhen = _.isRecoverType,
        ) match {
          case Some(error) => closeWithError(mark, error)
          case None => close(mark, TreeKind.Type.Tuple)
        }
        close(markType, TreeKind.Type.Type)
      }
      // Derivations.
      if (at(TokenKind.KeywordWith)) {
        Type.derivations()
      }

      // Check for illegal enum using both shorthand and body.
      if (isShorthand && eat(TokenKind.CurlyL)) {
        val mark = open()
        enumCases()
        expect(TokenKind.CurlyR)
        closeWithError(mark, WeederError.IllegalEnum(nameLoc))
      }

      // Enum body.
      if (eat(TokenKind.CurlyL)) {
        enumCases()
        expect(TokenKind.CurlyR)
      }
      close(mark, if (isRestrictable) TreeKind.Decl.RestrictableEnum else TreeKind.Decl.Enum)
    }

    private def FIRST_ENUM_CASE: Set[TokenKind] = Set(TokenKind.CommentDoc, TokenKind.KeywordCase, TokenKind.Comma)

    private def enumCases()(implicit s: State): Unit = {
      implicit val sctx: SyntacticContext = SyntacticContext.Decl.Enum
      // Clear non-doc comments that appear before any cases.
      comments()
      while (!eof() && atAny(FIRST_ENUM_CASE)) {
        val mark = open(consumeDocComments = false)
        docComment()
        if (!eat(TokenKind.KeywordCase)) {
          expect(TokenKind.Comma)
          // Handle comma followed by case keyword.
          docComment()
          eat(TokenKind.KeywordCase)
        }
        nameUnqualified(NAME_TAG)
        if (at(TokenKind.ParenL)) {
          val mark = open()
          val markTuple = open()
          oneOrMore(
            namedTokenSet = NamedTokenSet.Type,
            getItem = () => Type.ttype(),
            checkForItem = _.isFirstType,
            breakWhen = _.isRecoverDecl
          ) match {
            case Some(error) =>
              close(markTuple, TreeKind.Type.Tuple)
              closeWithError(mark, error)
            case None =>
              close(markTuple, TreeKind.Type.Tuple)
              close(mark, TreeKind.Type.Type)
          }
        }
        close(mark, TreeKind.Case)
      }
    }

    private def structDecl(mark: Mark.Opened)(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Decl.Struct
      assert(at(TokenKind.KeywordStruct))
      expect(TokenKind.KeywordStruct)
      nameUnqualified(NAME_TYPE)
      Type.parameters()
      zeroOrMore(
        namedTokenSet = NamedTokenSet.FromKinds(NAME_FIELD),
        getItem = structField,
        checkForItem = token => NAME_FIELD.contains(token) || token.isModifier,
        breakWhen = _.isRecoverExpr,
        delimiterL = TokenKind.CurlyL,
        delimiterR = TokenKind.CurlyR
      )
      close(mark, TreeKind.Decl.Struct)
    }

    private def structField()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Decl.Struct
      val mark = open()
      docComment()
      modifiers()
      nameUnqualified(NAME_FIELD)
      expect(TokenKind.Colon)
      Type.ttype()
      close(mark, TreeKind.StructField)
    }

    private def typeAliasDecl(mark: Mark.Opened)(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Decl.Type
      assert(at(TokenKind.KeywordType))
      expect(TokenKind.KeywordType)
      expect(TokenKind.KeywordAlias)
      nameUnqualified(NAME_TYPE)
      if (at(TokenKind.BracketL)) {
        Type.parameters()
      }
      if (eat(TokenKind.Equal)) {
        Type.ttype()
      }
      close(mark, TreeKind.Decl.TypeAlias)
    }

    private def associatedTypeSigDecl(mark: Mark.Opened)(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Decl.Module
      assert(at(TokenKind.KeywordType))
      expect(TokenKind.KeywordType)
      nameUnqualified(NAME_TYPE)
      if (at(TokenKind.BracketL)) {
        Type.parameters()
      }
      if (eat(TokenKind.Colon)) {
        Type.kind()
      }
      if (eat(TokenKind.Equal)) {
        Type.ttype()
      }
      close(mark, TreeKind.Decl.AssociatedTypeSig)
    }

    private def associatedTypeDefDecl(mark: Mark.Opened)(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Decl.Module
      expect(TokenKind.KeywordType)
      nameUnqualified(NAME_TYPE)
      if (at(TokenKind.BracketL)) {
        Type.arguments()
      }
      if (eat(TokenKind.Equal)) {
        Type.ttype()
      }
      close(mark, TreeKind.Decl.AssociatedTypeDef)
    }

    private def effectDecl(mark: Mark.Opened)(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Decl.Effect
      assert(at(TokenKind.KeywordEff))
      expect(TokenKind.KeywordEff)
      nameUnqualified(NAME_EFFECT)

      // Check for illegal type parameters.
      if (at(TokenKind.BracketL)) {
        val mark = open()
        val loc = currentSourceLocation()
        Type.parameters()
        closeWithError(mark, WeederError.IllegalEffectTypeParams(loc))
      }

      if (eat(TokenKind.CurlyL)) {
        var continue = true
        while (continue && !eof()) {
          val docMark = docComment()
          annotations()
          modifiers()
          nth(0) match {
            case TokenKind.CurlyR => continue = false
            case TokenKind.KeywordDef => operationDecl(openBefore(docMark))
            case at =>
              val errMark = open()
              val loc = currentSourceLocation()
              // Skip ahead until we hit another declaration or any CurlyR or EOF.
              while (!nth(0).isFirstEff && !eat(TokenKind.CurlyR) && !eof()) {
                advance()
              }
              val error = UnexpectedToken(expected = NamedTokenSet.FromKinds(Set(TokenKind.KeywordDef)), actual = Some(at), sctx, loc = loc)
              closeWithError(errMark, error, Some(at))
          }
        }
        expect(TokenKind.CurlyR)
      }
      close(mark, TreeKind.Decl.Effect)
    }

    private def operationDecl(mark: Mark.Opened)(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Decl.Module
      expect(TokenKind.KeywordDef)
      nameUnqualified(NAME_DEFINITION)

      // Check for illegal type parameters.
      if (at(TokenKind.BracketL)) {
        val mark = open()
        val loc = currentSourceLocation()
        Type.parameters()
        closeWithError(mark, WeederError.IllegalEffectTypeParams(loc))
      }

      if (at(TokenKind.ParenL)) {
        parameters()
      }
      expect(TokenKind.Colon)
      val typeLoc = currentSourceLocation()
      Type.ttype()
      // Check for illegal effect.
      if (at(TokenKind.Backslash)) {
        val mark = open()
        eat(TokenKind.Backslash)
        Type.ttype()
        closeWithError(mark, WeederError.IllegalEffectfulOperation(typeLoc))
      }
      if (at(TokenKind.KeywordWith)) {
        Type.constraints()
      }
      close(mark, TreeKind.Decl.Op)
    }

    private def modifiers()(implicit s: State): Mark.Closed = {
      val mark = open()
      while (nth(0).isModifier && !eof()) {
        advance()
      }
      close(mark, TreeKind.ModifierList)
    }

    def annotations()(implicit s: State): Mark.Closed = {
      val mark = open()
      while (at(TokenKind.Annotation) && !eof()) {
        advance()
      }
      close(mark, TreeKind.AnnotationList)
    }

    def docComment()(implicit s: State): Mark.Closed = {
      // `open` consumes doc-comments.
      val mark = open()
      close(mark, TreeKind.Doc)
    }

    def parameters()(implicit sctx: SyntacticContext, s: State): Mark.Closed = {
      val mark = open()
      zeroOrMore(
        namedTokenSet = NamedTokenSet.Parameter,
        getItem = () => parameter(),
        checkForItem = NAME_PARAMETER.contains,
        breakWhen = _.isRecoverParameters
      )
      close(mark, TreeKind.ParameterList)
    }

    private def parameter()(implicit sctx: SyntacticContext, s: State): Mark.Closed = {
      val mark = open()
      nameUnqualified(NAME_PARAMETER)
      if (eat(TokenKind.Colon)) {
        Type.ttype()
      }
      close(mark, TreeKind.Parameter)
    }

    private def equalityConstraints()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Decl.Module
      assert(at(TokenKind.KeywordWhere))
      val mark = open()
      expect(TokenKind.KeywordWhere)
      var continue = nth(0).isFirstType
      while (continue && !eof()) {
        val markConstraint = open()
        Type.ttype()
        expect(TokenKind.Tilde)
        Type.ttype()
        continue = eat(TokenKind.Comma)
        close(markConstraint, TreeKind.Decl.EqualityConstraintFragment)
      }
      close(mark, TreeKind.Decl.EqualityConstraintList)
    }
  }

  private object Expr {

    /**
      * Parse a statement, which is an expression optionally followed by a semi-colon and another
      * expression.
      *
      * @param rhsIsOptional If false, the right-hand-side expression is not treated as optional.
      */
    def statement(rhsIsOptional: Boolean = true)(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      var lhs = expression()
      if (eat(TokenKind.Semi)) {
        statement()
        lhs = close(openBefore(lhs), TreeKind.Expr.Statement)
        lhs = close(openBefore(lhs), TreeKind.Expr.Expr)
      } else if (!rhsIsOptional) {
        // If no semi is found and it was required, produce an error.
        // TODO: We could add a parse error hint as an argument to statement like:
        //       "Add an expression after the let-binding like so: 'let x = <expr1>; <expr2>'".
        expect(TokenKind.Semi)
      }
      lhs
    }

    def expression(left: TokenKind = TokenKind.Eof, leftIsUnary: Boolean = false)(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      var lhs = exprDelimited()
      // Handle chained calls and record lookups.
      var continue = true
      while (continue) {
        nth(0) match {
          case TokenKind.ParenL => // Function call.
            val mark = openBefore(lhs)
            arguments()
            lhs = close(mark, TreeKind.Expr.Apply)
            lhs = close(openBefore(lhs), TreeKind.Expr.Expr)
          case TokenKind.Dot if nth(1) == TokenKind.NameLowerCase => // Invoke method.
            val mark = openBefore(lhs)
            eat(TokenKind.Dot)
            nameUnqualified(Set(TokenKind.NameLowerCase))
            // `exp.f` is a Java field lookup and `exp.f(..)` is a Java method invocation.
            if (at(TokenKind.ParenL)) {
              arguments()
              lhs = close(mark, TreeKind.Expr.InvokeMethod)
            } else {
              lhs = close(mark, TreeKind.Expr.GetField)
            }
            lhs = close(openBefore(lhs), TreeKind.Expr.Expr)
          case TokenKind.Hash if nth(1) == TokenKind.NameLowerCase => // Record lookup.
            val mark = openBefore(lhs)
            eat(TokenKind.Hash)
            nameUnqualified(NAME_FIELD)
            lhs = close(mark, TreeKind.Expr.RecordSelect)
            lhs = close(openBefore(lhs), TreeKind.Expr.Expr)
          case TokenKind.StructArrow if nth(1) == TokenKind.NameLowerCase => // Struct get or put.
            val mark = openBefore(lhs)
            eat(TokenKind.StructArrow)
            nameUnqualified(NAME_FIELD)
            if (at(TokenKind.Equal)) { // Struct put.
              eat(TokenKind.Equal)
              val mark2 = open()
              expression()
              close(mark2, TreeKind.Expr.StructPutRHS)
              lhs = close(mark, TreeKind.Expr.StructPut)
              lhs = close(openBefore(lhs), TreeKind.Expr.Expr)
            } else { // Struct get.
              lhs = close(mark, TreeKind.Expr.StructGet)
              lhs = close(openBefore(lhs), TreeKind.Expr.Expr)
            }
          case TokenKind.BracketL =>
            val mark = openBefore(lhs)
            eat(TokenKind.BracketL)
            expression()
            expect(TokenKind.BracketR)
            if (at(TokenKind.Equal)) { // Index write.
              eat(TokenKind.Equal)
              expression()
              lhs = close(mark, TreeKind.Expr.IndexMut)
              lhs = close(openBefore(lhs), TreeKind.Expr.Expr)
            } else { // Index read.
              lhs = close(mark, TreeKind.Expr.Index)
              lhs = close(openBefore(lhs), TreeKind.Expr.Expr)
            }
          case _ => continue = false
        }
      }
      // Handle binary operators.
      continue = true
      while (continue) {
        val right = nth(0)
        if (rightBindsTighter(left, right, leftIsUnary)) {
          val mark = openBefore(lhs)
          val markOp = open()
          advance()
          close(markOp, TreeKind.Operator)
          expression(right)
          lhs = close(mark, TreeKind.Expr.Binary)
          lhs = close(openBefore(lhs), TreeKind.Expr.Expr)
        } else {
          // We want to allow an unbounded number of cons `a :: b :: c :: ...`.
          // Hence we special case on whether the left token is ::. If it is,
          // we avoid consuming any fuel.
          // The next nth lookup will always fail, so we add fuel to account for it.
          // The lookup for KeywordWithout will always happen so we add fuel to account for it.
          if (left == TokenKind.ColonColon) s.fuel += 2
          continue = false
        }
      }
      // Handle without expressions.
      if (eat(TokenKind.KeywordWithout)) {
        val mark = open()
        if (at(TokenKind.CurlyL)) {
          oneOrMore(
            namedTokenSet = NamedTokenSet.Effect,
            getItem = () => nameAllowQualified(NAME_EFFECT),
            checkForItem = NAME_EFFECT.contains,
            breakWhen = _.isRecoverExpr,
            delimiterL = TokenKind.CurlyL,
            delimiterR = TokenKind.CurlyR
          ) match {
            case Some(error) => closeWithError(open(), error)
            case _ =>
          }
        } else if (NAME_EFFECT.contains(nth(0))) {
          nameAllowQualified(NAME_EFFECT)
        } else {
          closeWithError(open(), UnexpectedToken(
            expected = NamedTokenSet.Effect,
            actual = Some(nth(0)),
            sctx = sctx,
            hint = Some(s"supply at least one effect to ${TokenKind.KeywordWithout.display}."),
            loc = previousSourceLocation()))
        }
        close(mark, TreeKind.Type.EffectSet)
        lhs = close(openBefore(lhs), TreeKind.Expr.Without)
        lhs = close(openBefore(lhs), TreeKind.Expr.Expr)
      }
      lhs
    }

    sealed trait OpKind

    private object OpKind {

      case object Unary extends OpKind

      case object Binary extends OpKind
    }

    /**
      * A precedence table for operators, lower is higher precedence.
      *
      * Note that [[OpKind]] is necessary for the cases where the same token kind can be both unary and binary, e.g., Plus or Minus.
      */
    private def PRECEDENCE: List[(OpKind, Array[TokenKind])] = List(
      (OpKind.Binary, Array(TokenKind.ColonEqual, TokenKind.KeywordInstanceOf)),
      (OpKind.Binary, Array(TokenKind.KeywordOr)),
      (OpKind.Binary, Array(TokenKind.KeywordAnd)),
      (OpKind.Binary, Array(TokenKind.TripleBar)),
      (OpKind.Binary, Array(TokenKind.TripleCaret)),
      (OpKind.Binary, Array(TokenKind.TripleAmpersand)),
      (OpKind.Binary, Array(TokenKind.EqualEqual, TokenKind.AngledEqual, TokenKind.BangEqual)),
      (OpKind.Binary, Array(TokenKind.AngleL, TokenKind.AngleR, TokenKind.AngleLEqual, TokenKind.AngleREqual)),
      (OpKind.Binary, Array(TokenKind.ColonColon, TokenKind.TripleColon)),
      (OpKind.Binary, Array(TokenKind.TripleAngleL, TokenKind.TripleAngleR)),
      (OpKind.Binary, Array(TokenKind.Plus, TokenKind.Minus)),
      (OpKind.Binary, Array(TokenKind.Star, TokenKind.StarStar, TokenKind.Slash)),
      (OpKind.Binary, Array(TokenKind.AngledPlus)),
      (OpKind.Unary, Array(TokenKind.KeywordDiscard)),
      (OpKind.Binary, Array(TokenKind.InfixFunction)),
      (OpKind.Binary, Array(TokenKind.UserDefinedOperator, TokenKind.NameMath)),
      (OpKind.Unary, Array(TokenKind.KeywordLazy, TokenKind.KeywordForce)),
      (OpKind.Unary, Array(TokenKind.Plus, TokenKind.Minus, TokenKind.TripleTilde)),
      (OpKind.Unary, Array(TokenKind.KeywordNot))
    )

    // These operators are right associative, e.g. "x :: y :: z" becomes
    // "x :: (y :: z)" rather than "(x :: y) :: z".
    private val rightAssoc: Array[TokenKind] = Array(TokenKind.ColonColon, TokenKind.TripleColon)

    private def rightBindsTighter(left: TokenKind, right: TokenKind, leftIsUnary: Boolean): Boolean = {
      def tightness(kind: TokenKind, opKind: OpKind = OpKind.Binary): Int = {
        PRECEDENCE.indexWhere { case (k, l) => k == opKind && l.contains(kind) }
      }

      val rt = tightness(right)
      if (rt == -1) {
        return false
      }
      val lt = tightness(left, if (leftIsUnary) OpKind.Unary else OpKind.Binary)
      if (lt == -1) {
        assert(left == TokenKind.Eof)
        return true
      }

      if (lt == rt && rightAssoc.contains(left)) true else rt > lt
    }

    private def arguments()(implicit s: State): Unit = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      if (nth(0) != TokenKind.ParenL) return
      val mark = open()
      zeroOrMore(
        namedTokenSet = NamedTokenSet.Expression,
        getItem = argument,
        // Remove KeywordDef from isFirstExpr for arguments only.
        // This handles the common case of incomplete arguments followed by another declaration gracefully.
        // For instance:
        // def foo(): Int32 = bar(
        // def main(): Unit = ()
        // In this example, if we had KeywordDef, main would be read as a LocalDef expression!
        checkForItem = kind => kind != TokenKind.KeywordDef && kind.isFirstExpr,
        breakWhen = _.isRecoverExpr
      )
      close(mark, TreeKind.ArgumentList)
    }

    private def argument()(implicit s: State): Mark.Closed = {
      val mark = open()
      expression()
      if (eat(TokenKind.Equal)) {
        expression()
        close(mark, TreeKind.ArgumentNamed)
      } else {
        close(mark, TreeKind.Argument)
      }
    }

    private def exprDelimited()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      // If a new expression is added here then add it to FIRST_EXPR too.
      val mark = open()
      nth(0) match {
        case TokenKind.KeywordOpenVariant => openVariantExpr()
        case TokenKind.KeywordOpenVariantAs => openVariantAsExpr()
        case TokenKind.HoleNamed
             | TokenKind.HoleAnonymous => holeExpr()
        case TokenKind.HoleVariable => holeVariableExpr()
        case TokenKind.KeywordUse => useExpr()
        case TokenKind.LiteralString
             | TokenKind.LiteralChar
             | TokenKind.LiteralFloat
             | TokenKind.LiteralFloat32
             | TokenKind.LiteralFloat64
             | TokenKind.LiteralBigDecimal
             | TokenKind.LiteralInt
             | TokenKind.LiteralInt8
             | TokenKind.LiteralInt16
             | TokenKind.LiteralInt32
             | TokenKind.LiteralInt64
             | TokenKind.LiteralBigInt
             | TokenKind.KeywordTrue
             | TokenKind.KeywordFalse
             | TokenKind.KeywordNull
             | TokenKind.LiteralRegex => literalExpr()
        case TokenKind.ParenL => parenOrTupleOrLambdaExpr()
        case TokenKind.Underscore => if (nth(1) == TokenKind.ArrowThinR) unaryLambdaExpr() else nameUnqualified(NAME_VARIABLE)
        case TokenKind.NameLowerCase if nth(1) == TokenKind.ArrowThinR => unaryLambdaExpr()
        case TokenKind.NameLowerCase => nameAllowQualified(NAME_FIELD)
        case TokenKind.NameUpperCase
             | TokenKind.NameMath
             | TokenKind.NameGreek => if (nth(1) == TokenKind.ArrowThinR) unaryLambdaExpr() else nameAllowQualified(NAME_DEFINITION)
        case TokenKind.Minus
             | TokenKind.KeywordNot
             | TokenKind.Plus
             | TokenKind.TripleTilde
             | TokenKind.KeywordLazy
             | TokenKind.KeywordForce
             | TokenKind.KeywordDiscard => unaryExpr()
        case TokenKind.KeywordIf => ifThenElseExpr()
        case TokenKind.KeywordLet => letMatchExpr()
        case TokenKind.Annotation | TokenKind.KeywordDef => localDefExpr()
        case TokenKind.KeywordRegion => scopeExpr()
        case TokenKind.KeywordMatch => matchOrMatchLambdaExpr()
        case TokenKind.KeywordTypeMatch => typematchExpr()
        case TokenKind.KeywordChoose
             | TokenKind.KeywordChooseStar => restrictableChooseExpr()
        case TokenKind.KeywordForA => forApplicativeExpr()
        case TokenKind.KeywordForeach => foreachExpr()
        case TokenKind.KeywordForM => forMonadicExpr()
        case TokenKind.CurlyL => blockOrRecordExpr()
        case TokenKind.ArrayHash => arrayLiteralExpr()
        case TokenKind.VectorHash => vectorLiteralExpr()
        case TokenKind.ListHash => listLiteralExpr()
        case TokenKind.SetHash => setLiteralExpr()
        case TokenKind.MapHash => mapLiteralExpr()
        case TokenKind.DotDotDot => dotdotdotLiteral()
        case TokenKind.KeywordCheckedCast => checkedTypeCastExpr()
        case TokenKind.KeywordCheckedECast => checkedEffectCastExpr()
        case TokenKind.KeywordUncheckedCast => uncheckedCastExpr()
        case TokenKind.KeywordUnsafe => unsafeExpr()
        case TokenKind.KeywordUnsafely => unsafelyRunExpr()
        case TokenKind.KeywordRun => runExpr()
        case TokenKind.KeywordHandler => handlerExpr()
        case TokenKind.KeywordTry => tryExpr()
        case TokenKind.KeywordThrow => throwExpr()
        case TokenKind.KeywordNew => ambiguousNewExpr()
        case TokenKind.KeywordStaticUppercase => staticExpr()
        case TokenKind.KeywordSelect => selectExpr()
        case TokenKind.KeywordSpawn => spawnExpr()
        case TokenKind.KeywordPar => parYieldExpr()
        case TokenKind.KeywordPSolve => fixpointSolveExpr(isPSolve = true)
        case TokenKind.HashCurlyL => fixpointConstraintSetExpr()
        case TokenKind.HashParenL => fixpointLambdaExpr()
        case TokenKind.KeywordPQuery => fixpointPQueryExpr()
        case TokenKind.KeywordSolve => fixpointSolveExpr(isPSolve = false)
        case TokenKind.KeywordInject => fixpointInjectExpr()
        case TokenKind.KeywordQuery => fixpointQueryExpr()
        case TokenKind.BuiltIn => intrinsicExpr()
        case TokenKind.LiteralStringInterpolationL
             | TokenKind.LiteralDebugStringL => interpolatedStringExpr()
        case TokenKind.KeywordDebug
             | TokenKind.KeywordDebugBang
             | TokenKind.KeywordDebugBangBang => debugExpr()
        case TokenKind.KeywordEMatch => extMatchExpr()
        case TokenKind.KeywordXvar => extTagExpr()
        case t =>
          val mark = open()
          val error = UnexpectedToken(expected = NamedTokenSet.Expression, actual = Some(t), sctx, loc = currentSourceLocation())
          advance()
          closeWithError(mark, error)
      }
      close(mark, TreeKind.Expr.Expr)
    }

    private def openVariantExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordOpenVariant))
      val mark = open()
      expect(TokenKind.KeywordOpenVariant)
      nameAllowQualified(NAME_QNAME)
      close(mark, TreeKind.Expr.OpenVariant)
    }

    private def openVariantAsExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordOpenVariantAs))
      val mark = open()
      expect(TokenKind.KeywordOpenVariantAs)
      nameAllowQualified(NAME_QNAME)
      expression()
      close(mark, TreeKind.Expr.OpenVariantAs)
    }

    private def holeExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(atAny(Set(TokenKind.HoleNamed, TokenKind.HoleAnonymous)))
      val mark = open()
      nth(0) match {
        case TokenKind.HoleAnonymous =>
          advance()
          close(mark, TreeKind.Expr.Hole)
        case TokenKind.HoleNamed =>
          nameUnqualified(Set(TokenKind.HoleNamed))
          close(mark, TreeKind.Expr.Hole)
        case _ => throw InternalCompilerException("Parser assert missed case", currentSourceLocation())
      }
    }

    private def holeVariableExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.HoleVariable))
      val mark = open()
      nameUnqualified(Set(TokenKind.HoleVariable))
      close(mark, TreeKind.Expr.HoleVariable)
    }

    private def useExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      val mark = open()
      use()
      expect(TokenKind.Semi)
      statement()
      close(mark, TreeKind.Expr.Use)
    }

    private def literalExpr()(implicit s: State): Mark.Closed = {
      val mark = open()
      advance()
      close(mark, TreeKind.Expr.Literal)
    }

    private def parenOrTupleOrLambdaExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.ParenL))
      (nth(0), nth(1)) match {
        // Detect unit tuple.
        case (TokenKind.ParenL, TokenKind.ParenR) =>
          // Detect unit lambda `() -> expr`.
          if (nth(2) == TokenKind.ArrowThinR) {
            lambda()
          } else {
            val mark = open()
            advance()
            advance()
            close(mark, TreeKind.Expr.Tuple)
          }

        case (TokenKind.ParenL, _) =>
          // Detect lambda function declaration.
          val isLambda = {
            var level = 1
            var curlyLevel = 0
            var lookAhead = 0
            val tokensLeft = s.tokens.length - s.position
            while (level > 0 && lookAhead < tokensLeft && !eof()) {
              lookAhead += 1
              nth(lookAhead) match {
                case TokenKind.ParenL => level += 1
                case TokenKind.ParenR => level -= 1
                case TokenKind.CurlyL | TokenKind.HashCurlyL => curlyLevel += 1
                case TokenKind.CurlyR if level == 1 =>
                  if (curlyLevel == 0) {
                    // Hitting '}' on top-level is a clear indicator that something is wrong,
                    // most likely the terminating ')' was forgotten.
                    return advanceWithError(Malformed(NamedTokenSet.Tuple, sctx, loc = currentSourceLocation()))
                  } else {
                    curlyLevel -= 1
                  }
                case TokenKind.Eof => return advanceWithError(Malformed(NamedTokenSet.Tuple, sctx, loc = currentSourceLocation()))
                case _ =>
              }
            }
            nth(lookAhead + 1) == TokenKind.ArrowThinR
          }

          if (isLambda) {
            lambda()
          } else {
            parenOrTupleOrAscribe()
          }

        case (t, _) =>
          val error = UnexpectedToken(expected = NamedTokenSet.FromKinds(Set(TokenKind.ParenL)), actual = Some(t), sctx, loc = currentSourceLocation())
          advanceWithError(error)
      }
    }

    private def lambda()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      val mark = open()
      Decl.parameters()
      expect(TokenKind.ArrowThinR)
      expression()
      close(mark, TreeKind.Expr.Lambda)
    }

    private def parenOrTupleOrAscribe()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      val mark = open()
      expect(TokenKind.ParenL)
      val markExpr = expression()
      // Distinguish between expression in parenthesis, type ascriptions, and tuples.
      nth(0) match {
        // Type ascription.
        case TokenKind.Colon =>
          expect(TokenKind.Colon)
          Type.typeAndEffect()
          expect(TokenKind.ParenR)
          close(mark, TreeKind.Expr.Ascribe)
        // Tuple.
        case TokenKind.Equal | TokenKind.Comma =>
          if (eat(TokenKind.Equal)) {
            expression()
            close(openBefore(markExpr), TreeKind.ArgumentNamed)
          } else {
            close(openBefore(markExpr), TreeKind.Argument)
          }
          while (!at(TokenKind.ParenR) && !eof()) {
            eat(TokenKind.Comma)
            argument()
          }
          expect(TokenKind.ParenR)
          close(mark, TreeKind.Expr.Tuple)
        // Paren.
        case _ =>
          expect(TokenKind.ParenR)
          close(mark, TreeKind.Expr.Paren)
      }
    }

    private def extMatchExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordEMatch))
      val mark = open()
      expect(TokenKind.KeywordEMatch)
      expression()
      oneOrMore(
        namedTokenSet = NamedTokenSet.ExtMatchRule,
        checkForItem = _ == TokenKind.KeywordCase,
        getItem = extMatchRule,
        breakWhen = _.isRecoverExpr,
        delimiterL = TokenKind.CurlyL,
        delimiterR = TokenKind.CurlyR,
        separation = Separation.Optional(TokenKind.Comma)
      )
      close(mark, TreeKind.Expr.ExtMatch)
    }
    private def extTagExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordXvar))
      expect(TokenKind.KeywordXvar)
      val lhs = nameUnqualified(NAME_TAG)
      val mark = openBefore(lhs)
      nth(0) match {
        case TokenKind.ParenL => arguments()
        case _ => ()
      }
      close(mark, TreeKind.Expr.ExtTag)
    }

    private def unaryLambdaExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      val mark = open()
      val markParams = open()
      val markParam = open()
      nameUnqualified(NAME_PARAMETER)
      close(markParam, TreeKind.Parameter)
      close(markParams, TreeKind.ParameterList)
      expect(TokenKind.ArrowThinR)
      expression()
      close(mark, TreeKind.Expr.Lambda)
    }

    private val FIRST_EXPR_UNARY: Set[TokenKind] = Set(
      TokenKind.Minus,
      TokenKind.KeywordNot,
      TokenKind.Plus,
      TokenKind.TripleTilde,
      TokenKind.KeywordLazy,
      TokenKind.KeywordForce,
      TokenKind.KeywordDiscard
    )

    private def unaryExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      val mark = open()
      val op = nth(0)
      val markOp = open()
      expectAny(FIRST_EXPR_UNARY)
      close(markOp, TreeKind.Operator)
      expression(left = op, leftIsUnary = true)
      close(mark, TreeKind.Expr.Unary)
    }

    private def ifThenElseExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordIf))
      val mark = open()
      expect(TokenKind.KeywordIf)
      expect(TokenKind.ParenL)
      expression()
      expect(TokenKind.ParenR)
      expression()
      if (eat(TokenKind.KeywordElse)) {
        // Only call expression, if we found an 'else'. Otherwise when it is missing, defs might
        // get read as let-rec-defs.
        expression()
      }
      close(mark, TreeKind.Expr.IfThenElse)
    }

    private def letMatchExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordLet))
      val mark = open()
      expect(TokenKind.KeywordLet)
      Pattern.pattern()
      if (eat(TokenKind.Colon)) {
        Type.ttype()
      }
      expect(TokenKind.Equal)
      statement(rhsIsOptional = false)
      close(mark, TreeKind.Expr.LetMatch)
    }

    private def localDefExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(atAny(Set(TokenKind.Annotation, TokenKind.KeywordDef, TokenKind.CommentDoc)))
      val mark = open(consumeDocComments = false)
      Decl.docComment()
      Decl.annotations()
      expect(TokenKind.KeywordDef)
      nameUnqualified(NAME_DEFINITION)
      Decl.parameters()
      if (eat(TokenKind.Colon)) {
        Type.typeAndEffect()
      }
      expect(TokenKind.Equal)
      statement(rhsIsOptional = false)
      close(mark, TreeKind.Expr.LocalDef)
    }

    private def scopeExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordRegion))
      val mark = open()
      expect(TokenKind.KeywordRegion)
      nameUnqualified(NAME_VARIABLE)
      if (at(TokenKind.CurlyL)) {
        block()
      }
      close(mark, TreeKind.Expr.Scope)
    }

    private def block()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.CurlyL))
      val mark = open()
      expect(TokenKind.CurlyL)
      if (eat(TokenKind.CurlyR)) { // Handle an empty block.
        return close(mark, TreeKind.Expr.LiteralRecord)
      }
      statement()
      expect(TokenKind.CurlyR)
      close(mark, TreeKind.Expr.Block)
    }

    private val FIRST_EXPR_DEBUG: Set[TokenKind] = Set(
      TokenKind.KeywordDebug,
      TokenKind.KeywordDebugBang,
      TokenKind.KeywordDebugBangBang
    )

    private def debugExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(atAny(FIRST_EXPR_DEBUG))
      val mark = open()
      expectAny(FIRST_EXPR_DEBUG)
      expect(TokenKind.ParenL)
      expression()
      expect(TokenKind.ParenR)
      close(mark, TreeKind.Expr.Debug)
    }

    private def matchOrMatchLambdaExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordMatch))
      val mark = open()
      expect(TokenKind.KeywordMatch)
      // Detect match lambda.
      val isLambda = {
        var lookAhead = 0
        var isLambda = false
        var continue = true
        // We need to track the parenthesis nesting level to handle match-expressions
        // that include lambdas, e.g., `match f(x -> g(x)) { case ... }`.
        // In these cases the ArrowThin __does not__ indicate that the expression being parsed is
        // a match lambda.
        var parenNestingLevel = 0
        while (continue && !eof()) {
          nth(lookAhead) match {
            // match `expr { case ... }`.
            case TokenKind.KeywordCase => continue = false
            // match `pattern -> expr`.
            case TokenKind.ArrowThinR if parenNestingLevel == 0 => isLambda = true; continue = false
            case TokenKind.ParenL => parenNestingLevel += 1; lookAhead += 1
            case TokenKind.ParenR => parenNestingLevel -= 1; lookAhead += 1
            case TokenKind.Eof =>
              val error = UnexpectedToken(expected = NamedTokenSet.Expression, actual = None, sctx, loc = currentSourceLocation())
              return closeWithError(mark, error)
            case t if t.isFirstDecl =>
              // Advance past the erroneous region to the next stable token
              // (the start of the declaration).
              for (_ <- 0 until lookAhead) {
                advance()
              }
              val error = UnexpectedToken(expected = NamedTokenSet.Expression, actual = Some(t), sctx, loc = currentSourceLocation())
              return closeWithError(mark, error)
            case _ => lookAhead += 1
          }
        }
        isLambda
      }

      if (isLambda) {
        Pattern.pattern()
        expect(TokenKind.ArrowThinR)
        expression()
        close(mark, TreeKind.Expr.LambdaMatch)
      } else {
        expression()
        oneOrMore(
          namedTokenSet = NamedTokenSet.MatchRule,
          checkForItem = _ == TokenKind.KeywordCase,
          getItem = matchRule,
          breakWhen = _.isRecoverExpr,
          delimiterL = TokenKind.CurlyL,
          delimiterR = TokenKind.CurlyR,
          separation = Separation.Optional(TokenKind.Comma)
        )
        close(mark, TreeKind.Expr.Match)
      }
    }

    private def matchRule()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordCase))
      val mark = open()
      expect(TokenKind.KeywordCase)
      Pattern.pattern()
      if (eat(TokenKind.KeywordIf)) {
        expression()
      }
      if (eat(TokenKind.Equal)) {
        val error = UnexpectedToken(
          NamedTokenSet.FromKinds(Set(TokenKind.ArrowThickR)),
          actual = Some(TokenKind.Equal),
          sctx = sctx,
          hint = Some("use '=>' instead of '='."),
          loc = previousSourceLocation())
        closeWithError(open(), error)
      } else {
        expect(TokenKind.ArrowThickR)
      }
      statement()
      close(mark, TreeKind.Expr.MatchRuleFragment)
    }

    private def extMatchRule()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordCase))
      val mark = open()
      expect(TokenKind.KeywordCase)
      Pattern.extPattern()
      if (eat(TokenKind.Equal)) {
        val error = UnexpectedToken(
          NamedTokenSet.FromKinds(Set(TokenKind.ArrowThickR)),
          actual = Some(TokenKind.Equal),
          sctx = sctx,
          hint = Some("use '=>' instead of '='."),
          loc = previousSourceLocation())
        closeWithError(open(), error)
      } else {
        expect(TokenKind.ArrowThickR)
      }
      statement()
      close(mark, TreeKind.Expr.ExtMatchRuleFragment)
    }

    private def typematchExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordTypeMatch))
      val mark = open()
      expect(TokenKind.KeywordTypeMatch)
      expression()
      oneOrMore(
        namedTokenSet = NamedTokenSet.MatchRule,
        checkForItem = _ == TokenKind.KeywordCase,
        getItem = typematchRule,
        breakWhen = _.isRecoverExpr,
        delimiterL = TokenKind.CurlyL,
        delimiterR = TokenKind.CurlyR,
        separation = Separation.Optional(TokenKind.Comma)
      )
      close(mark, TreeKind.Expr.TypeMatch)
    }

    private def typematchRule()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordCase))
      val mark = open()
      expect(TokenKind.KeywordCase)
      nameUnqualified(NAME_VARIABLE)
      if (eat(TokenKind.Colon)) {
        Type.ttype()
      }
      // TODO: It's common to type '=' instead of '=>' here. Should we make a specific error?
      if (eat(TokenKind.ArrowThickR)) {
        statement()
      }
      close(mark, TreeKind.Expr.TypeMatchRuleFragment)
    }

    private def restrictableChooseExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(atAny(Set(TokenKind.KeywordChoose, TokenKind.KeywordChooseStar)))
      val mark = open()
      val isStar = eat(TokenKind.KeywordChooseStar)
      if (!isStar) {
        expect(TokenKind.KeywordChoose)
      }
      expression()
      oneOrMore(
        namedTokenSet = NamedTokenSet.MatchRule,
        checkForItem = _ == TokenKind.KeywordCase,
        getItem = matchRule,
        breakWhen = _.isRecoverExpr,
        delimiterL = TokenKind.CurlyL,
        delimiterR = TokenKind.CurlyR,
        separation = Separation.Optional(TokenKind.Comma)
      )
      close(mark, if (isStar) TreeKind.Expr.RestrictableChooseStar else TreeKind.Expr.RestrictableChoose)
    }

    private def forApplicativeExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordForA))
      val mark = open()
      expect(TokenKind.KeywordForA)
      // Only generator patterns are allowed here - Weeder must verify this.
      forFragments()
      expect(TokenKind.KeywordYield)
      expression()
      close(mark, TreeKind.Expr.ForApplicative)
    }

    private def foreachExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordForeach))
      val mark = open()
      var kind: TreeKind = TreeKind.Expr.Foreach
      expect(TokenKind.KeywordForeach)
      forFragments()
      if (eat(TokenKind.KeywordYield)) {
        kind = TreeKind.Expr.ForeachYield
      }
      expression()
      close(mark, kind)
    }

    private def forMonadicExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordForM))
      val mark = open()
      expect(TokenKind.KeywordForM)
      forFragments()
      expect(TokenKind.KeywordYield)
      expression()
      close(mark, TreeKind.Expr.ForMonadic)
    }

    private def forFragments()(implicit s: State): Unit = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      oneOrMore(
        namedTokenSet = NamedTokenSet.ForFragment,
        checkForItem = t => t.isFirstPattern || t == TokenKind.KeywordIf,
        getItem = () =>
          if (at(TokenKind.KeywordIf)) {
            guardFragment()
          } else {
            generatorOrLetFragment()
          },
        breakWhen = t => t == TokenKind.KeywordYield || t.isRecoverExpr,
        separation = Separation.Required(TokenKind.Semi)
      )
    }

    private def guardFragment()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordIf))
      val mark = open()
      expect(TokenKind.KeywordIf)
      expression()
      close(mark, TreeKind.Expr.ForFragmentGuard)
    }

    private def generatorOrLetFragment()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      val mark = open()
      Pattern.pattern()
      val isGenerator = eat(TokenKind.ArrowThinL)
      if (!isGenerator) {
        expect(TokenKind.Equal)
      }
      expression()
      close(mark, if (isGenerator) {
        TreeKind.Expr.ForFragmentGenerator
      } else {
        TreeKind.Expr.ForFragmentLet
      })
    }

    private def blockOrRecordExpr()(implicit s: State): Mark.Closed = {
      // Determines if a '{' is opening a block, a record literal, or a record operation.
      assert(at(TokenKind.CurlyL))

      /** Returns the distance to the first non-comment token after lookahead. */
      @tailrec
      def nextNonComment(lookahead: Int): Int = {
        if (s.position + lookahead > s.tokens.length - 1) {
          lookahead
        } else s.tokens(s.position + lookahead).kind match {
          case t if t.isComment => nextNonComment(lookahead + 1)
          case _ => lookahead
        }
      }

      // We can discern between record ops and literals vs. blocks by looking at the next two
      // non-comment tokens.
      val nextTwoNonCommentTokens = {
        val nextIdx = nextNonComment(1)
        val nextNextIdx = nextNonComment(nextIdx + 1)
        (nth(nextIdx), nth(nextNextIdx))
      }
      nextTwoNonCommentTokens match {
        case (TokenKind.CurlyR, _)
             | (TokenKind.NameLowerCase, TokenKind.Equal)
             | (TokenKind.Plus, TokenKind.NameLowerCase)
             | (TokenKind.Minus, TokenKind.NameLowerCase) =>
          // Now check for record operation or record literal,
          // by looking for a '|' before the closing '}'.
          val isRecordOp = {
            val tokensLeft = s.tokens.length - s.position
            var lookahead = 1
            var nestingLevel = 0
            var isRecordOp = false
            var continue = true
            while (continue && lookahead < tokensLeft) {
              nth(lookahead) match {
                // Found closing '}' so stop seeking.
                case TokenKind.CurlyR if nestingLevel == 0 => continue = false
                // Found '|' before closing '}' which means that it is a record operation.
                case TokenKind.Bar if nestingLevel == 0 =>
                  isRecordOp = true
                  continue = false
                case TokenKind.CurlyL | TokenKind.HashCurlyL =>
                  nestingLevel += 1
                  lookahead += 1
                case TokenKind.CurlyR =>
                  nestingLevel -= 1
                  lookahead += 1
                case _ => lookahead += 1
              }
            }
            isRecordOp
          }
          if (isRecordOp) {
            recordOperation()
          } else {
            recordLiteral()
          }
        case _ => block()
      }
    }

    private def recordLiteral()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.CurlyL))
      val mark = open()
      zeroOrMore(
        namedTokenSet = NamedTokenSet.FromKinds(NAME_FIELD),
        getItem = recordLiteralField,
        checkForItem = NAME_FIELD.contains,
        breakWhen = _.isRecoverExpr,
        delimiterL = TokenKind.CurlyL,
        delimiterR = TokenKind.CurlyR
      )
      close(mark, TreeKind.Expr.LiteralRecord)
    }

    private def recordLiteralField()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      val mark = open()
      nameUnqualified(NAME_FIELD)
      expect(TokenKind.Equal)
      expression()
      close(mark, TreeKind.Expr.LiteralRecordFieldFragment)
    }


    private def recordOperation()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.CurlyL))
      val mark = open()
      oneOrMore(
        namedTokenSet = NamedTokenSet.FromKinds(Set(TokenKind.Plus, TokenKind.Minus, TokenKind.NameLowerCase)),
        getItem = recordOp,
        checkForItem = _.isFirstRecordOp,
        breakWhen = _.isRecoverExpr,
        delimiterL = TokenKind.CurlyL,
        delimiterR = TokenKind.CurlyR,
        optionallyWith = Some((TokenKind.Bar, () => expression()))
      ) match {
        case Some(error) => closeWithError(mark, error)
        case None => close(mark, TreeKind.Expr.RecordOperation)
      }
    }

    private def recordOp()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      val mark = open()
      nth(0) match {
        case TokenKind.Plus =>
          advance()
          nameUnqualified(NAME_FIELD)
          expect(TokenKind.Equal)
          expression()
          close(mark, TreeKind.Expr.RecordOpExtend)
        case TokenKind.Minus =>
          advance()
          nameUnqualified(NAME_FIELD)
          close(mark, TreeKind.Expr.RecordOpRestrict)
        case TokenKind.NameLowerCase =>
          nameUnqualified(NAME_FIELD)
          expect(TokenKind.Equal)
          expression()
          close(mark, TreeKind.Expr.RecordOpUpdate)
        case at =>
          val error = UnexpectedToken(expected = NamedTokenSet.FromKinds(Set(TokenKind.Plus, TokenKind.Minus, TokenKind.NameLowerCase)), actual = Some(at), sctx, loc = currentSourceLocation())
          advanceWithError(error, Some(mark))
      }
    }

    private def arrayLiteralExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.ArrayHash))
      val mark = open()
      expect(TokenKind.ArrayHash)
      zeroOrMore(
        namedTokenSet = NamedTokenSet.Expression,
        getItem = () => expression(),
        checkForItem = _.isFirstExpr,
        breakWhen = _.isRecoverExpr,
        delimiterL = TokenKind.CurlyL,
        delimiterR = TokenKind.CurlyR
      )
      scopeName()
      close(mark, TreeKind.Expr.LiteralArray)
    }

    private def scopeName()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      val mark = open()
      expect(TokenKind.At)
      expression()
      close(mark, TreeKind.Expr.ScopeName)
    }

    private def vectorLiteralExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.VectorHash))
      val mark = open()
      expect(TokenKind.VectorHash)
      zeroOrMore(
        namedTokenSet = NamedTokenSet.Expression,
        getItem = () => expression(),
        checkForItem = _.isFirstExpr,
        breakWhen = _.isRecoverExpr,
        delimiterL = TokenKind.CurlyL,
        delimiterR = TokenKind.CurlyR
      )
      close(mark, TreeKind.Expr.LiteralVector)
    }

    private def listLiteralExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.ListHash))
      val mark = open()
      expect(TokenKind.ListHash)
      zeroOrMore(
        namedTokenSet = NamedTokenSet.Expression,
        getItem = () => expression(),
        checkForItem = _.isFirstExpr,
        breakWhen = _.isRecoverExpr,
        delimiterL = TokenKind.CurlyL,
        delimiterR = TokenKind.CurlyR
      )
      close(mark, TreeKind.Expr.LiteralList)
    }

    private def setLiteralExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.SetHash))
      val mark = open()
      expect(TokenKind.SetHash)
      zeroOrMore(
        namedTokenSet = NamedTokenSet.Expression,
        getItem = () => expression(),
        checkForItem = _.isFirstExpr,
        breakWhen = _.isRecoverExpr,
        delimiterL = TokenKind.CurlyL,
        delimiterR = TokenKind.CurlyR
      )
      close(mark, TreeKind.Expr.LiteralSet)
    }

    private def mapLiteralExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.MapHash))
      val mark = open()
      expect(TokenKind.MapHash)
      zeroOrMore(
        namedTokenSet = NamedTokenSet.Expression,
        getItem = mapLiteralValue,
        checkForItem = _.isFirstExpr,
        breakWhen = _.isRecoverExpr,
        delimiterL = TokenKind.CurlyL,
        delimiterR = TokenKind.CurlyR
      )
      close(mark, TreeKind.Expr.LiteralMap)
    }

    private def mapLiteralValue()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      val mark = open()
      expression()
      expect(TokenKind.ArrowThickR)
      expression()
      close(mark, TreeKind.Expr.LiteralMapKeyValueFragment)
    }

    private def dotdotdotLiteral()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.DotDotDot))
      val mark = open()
      expect(TokenKind.DotDotDot)
      zeroOrMore(
        namedTokenSet = NamedTokenSet.Expression,
        getItem = () => expression(),
        checkForItem = _.isFirstExpr,
        breakWhen = _.isRecoverExpr,
        delimiterL = TokenKind.CurlyL,
        delimiterR = TokenKind.CurlyR
      )
      close(mark, TreeKind.Expr.LiteralVector)
    }

    private def checkedTypeCastExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordCheckedCast))
      val mark = open()
      expect(TokenKind.KeywordCheckedCast)
      if (eat(TokenKind.ParenL)) {
        expression()
        expect(TokenKind.ParenR)
      }
      close(mark, TreeKind.Expr.CheckedTypeCast)
    }

    private def checkedEffectCastExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordCheckedECast))
      val mark = open()
      expect(TokenKind.KeywordCheckedECast)
      if (eat(TokenKind.ParenL)) {
        expression()
        expect(TokenKind.ParenR)
      }
      close(mark, TreeKind.Expr.CheckedEffectCast)
    }

    private def uncheckedCastExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordUncheckedCast))
      val mark = open()
      expect(TokenKind.KeywordUncheckedCast)
      if (eat(TokenKind.ParenL)) {
        expression()
        if (eat(TokenKind.KeywordAs)) {
          Type.typeAndEffect()
        }
        expect(TokenKind.ParenR)
      }
      close(mark, TreeKind.Expr.UncheckedCast)
    }

    private def unsafeExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordUnsafe))
      val mark = open()
      expect(TokenKind.KeywordUnsafe)
      expression()
      close(mark, TreeKind.Expr.UnsafeOld)
    }

    /**
      * `'unsafely' TTYPE 'run' EXPRESSION`
      *
      * produces
      *
      *   - TreeKind.Expr.UnsafelyRun
      *     - TreeKind.Type.Type
      *     - TreeKind.Expr.Expr
      */
    private def unsafelyRunExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordUnsafely))
      val mark = open()
      expect(TokenKind.KeywordUnsafely)
      Type.ttype()
      expect(TokenKind.KeywordRun)
      expression()
      close(mark, TreeKind.Expr.Unsafe)
    }

    /**
      * `'run' EXPRESSION ('with' EXPRESSION)*`
      *
      * produces
      *   - TreeKind.Expr.Run
      *     - TreeKind.Expr.RunWithBodyExpr
      *     - TreeKind.Expr.RunWithBodyExpr
      *     - .. (potentially zero)
      */
    private def runExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordRun))
      val mark = open()
      expect(TokenKind.KeywordRun)
      expression()
      while (at(TokenKind.KeywordWith)) {
        withBody()
      }
      close(mark, TreeKind.Expr.Run)
    }

    private def handlerExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordHandler))
      val mark = open()
      expect(TokenKind.KeywordHandler)
      nameAllowQualified(NAME_EFFECT)
      if (at(TokenKind.CurlyL)) {
        zeroOrMore(
          namedTokenSet = NamedTokenSet.WithRule,
          getItem = withRule,
          checkForItem = kind => kind == TokenKind.KeywordDef || kind.isComment,
          breakWhen = _.isRecoverExpr,
          separation = Separation.Optional(TokenKind.Comma),
          delimiterL = TokenKind.CurlyL,
          delimiterR = TokenKind.CurlyR
        )
      } else {
        s.errors.append(ParseError.UnexpectedToken(expected = NamedTokenSet.FromKinds(Set(TokenKind.CurlyL)), actual = Some(nth(0)), sctx = sctx, loc = currentSourceLocation()))
      }
      close(mark, TreeKind.Expr.Handler)
    }

    private def tryExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordTry))
      val mark = open()
      expect(TokenKind.KeywordTry)
      expression()
      while (at(TokenKind.KeywordCatch)) {
        catchBody()
      }
      close(mark, TreeKind.Expr.Try)
    }

    private def catchBody()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordCatch))
      val mark = open()
      expect(TokenKind.KeywordCatch)
      oneOrMore(
        namedTokenSet = NamedTokenSet.CatchRule,
        getItem = catchRule,
        checkForItem = _ == TokenKind.KeywordCase,
        breakWhen = _.isRecoverExpr,
        separation = Separation.Optional(TokenKind.Comma),
        delimiterL = TokenKind.CurlyL,
        delimiterR = TokenKind.CurlyR
      ) match {
        case Some(error) => closeWithError(mark, error)
        case None => close(mark, TreeKind.Expr.TryCatchBodyFragment)
      }
    }

    private def catchRule()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      val mark = open()
      expect(TokenKind.KeywordCase)
      nameUnqualified(NAME_VARIABLE)
      expect(TokenKind.Colon)
      nameAllowQualified(NAME_JAVA, tail = Set())
      expect(TokenKind.ArrowThickR)
      statement()
      close(mark, TreeKind.Expr.TryCatchRuleFragment)
    }

    private def withBody()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordWith))
      val mark = open()
      expect(TokenKind.KeywordWith)
      expression()
      close(mark, TreeKind.Expr.RunWithBodyExpr)
    }

    private def withRule()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(nth(0).isComment || at(TokenKind.KeywordDef))
      val mark = open()
      expect(TokenKind.KeywordDef)
      nameUnqualified(Set(TokenKind.NameLowerCase))
      Decl.parameters()
      expect(TokenKind.Equal)
      expression()
      close(mark, TreeKind.Expr.RunWithRuleFragment)
    }

    private def throwExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordThrow))
      val mark = open()
      expect(TokenKind.KeywordThrow)
      expression()
      close(mark, TreeKind.Expr.Throw)
    }

    private def ambiguousNewExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordNew))
      val mark = open()
      expect(TokenKind.KeywordNew)
      Type.ttype()

      // Either NewStruct, NewObject, or InvokeConstructor.
      if (at(TokenKind.At)) {
        // Either `new Struct @ rc {field1 = expr1, field2 = expr2, ...}`
        //     or `new Struct @ rc {}`.
        expect(TokenKind.At)
        expression()
        if (!at(TokenKind.CurlyL)) {
          expect(TokenKind.CurlyL)
        }
        else {
          zeroOrMore(
            namedTokenSet = NamedTokenSet.FromKinds(NAME_FIELD),
            checkForItem = NAME_FIELD.contains,
            getItem = structFieldInit,
            breakWhen = _.isRecoverExpr,
            separation = Separation.Required(TokenKind.Comma),
            delimiterL = TokenKind.CurlyL,
            delimiterR = TokenKind.CurlyR
          )
        }
        close(mark, TreeKind.Expr.NewStruct)
      } else if (at(TokenKind.CurlyL)) {
        // `new Type { ... }`.
        zeroOrMore(
          namedTokenSet = NamedTokenSet.FromKinds(Set(TokenKind.KeywordDef)),
          checkForItem = t => t.isComment || t == TokenKind.KeywordDef,
          getItem = jvmMethod,
          breakWhen = _.isRecoverExpr,
          delimiterL = TokenKind.CurlyL,
          delimiterR = TokenKind.CurlyR,
          separation = Separation.None
        )
        close(mark, TreeKind.Expr.NewObject)
      } else {
        // `new Type(exps...)`.
        arguments()
        close(mark, TreeKind.Expr.InvokeConstructor)
      }
    }

    private def structFieldInit()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      val mark = open()
      nameUnqualified(NAME_FIELD)
      expect(TokenKind.Equal)
      expression()
      close(mark, TreeKind.Expr.LiteralStructFieldFragment)
    }

    private def jvmMethod()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordDef))
      val mark = open()
      expect(TokenKind.KeywordDef)
      nameUnqualified(NAME_JAVA)
      Decl.parameters()
      expect(TokenKind.Colon)
      Type.typeAndEffect()
      expect(TokenKind.Equal)
      Expr.statement()
      close(mark, TreeKind.Expr.JvmMethod)
    }

    private def staticExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordStaticUppercase))
      val mark = open()
      expect(TokenKind.KeywordStaticUppercase)
      close(mark, TreeKind.Expr.Static)
    }

    private def selectExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordSelect))
      val mark = open()
      expect(TokenKind.KeywordSelect)
      expect(TokenKind.CurlyL)
      comments()
      // We can't use zeroOrMore here because there's special semantics.
      // The wildcard rule `case _ => ...` must be the last one.
      var continue = true
      while (continue && at(TokenKind.KeywordCase) && !eof()) {
        val ruleMark = open()
        expect(TokenKind.KeywordCase)
        val isDefault = findBefore(TokenKind.ArrowThickR, Array(TokenKind.ArrowThinL))
        if (isDefault) {
          // Only the last rule can be a wildcard rule so stop after this one.
          continue = false
          expect(TokenKind.Underscore)
          expect(TokenKind.ArrowThickR)
          statement()
          close(ruleMark, TreeKind.Expr.SelectRuleDefaultFragment)
        } else {
          nameUnqualified(NAME_VARIABLE)
          expect(TokenKind.ArrowThinL)
          // Note that only "Channel.recv" and "recv" are allowed for this name.
          // We don't want to reserve "Channel" and "recv" as keywords,
          // so parsing it as a qname is fine.
          nameAllowQualified(NAME_QNAME)
          expect(TokenKind.ParenL)
          expression()
          expect(TokenKind.ParenR)
          expect(TokenKind.ArrowThickR)
          statement()
          close(ruleMark, TreeKind.Expr.SelectRuleFragment)
        }
      }
      expect(TokenKind.CurlyR)

      close(mark, TreeKind.Expr.Select)
    }

    private def spawnExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordSpawn))
      val mark = open()
      expect(TokenKind.KeywordSpawn)
      expression()
      scopeName()
      close(mark, TreeKind.Expr.Spawn)
    }

    private def parYieldExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordPar))
      val mark = open()
      expect(TokenKind.KeywordPar)
      if (at(TokenKind.ParenL)) {
        oneOrMore(
          namedTokenSet = NamedTokenSet.Pattern,
          getItem = parYieldFragment,
          checkForItem = _.isFirstPattern,
          separation = Separation.Required(TokenKind.Semi),
          breakWhen = kind => kind == TokenKind.KeywordYield || kind.isRecoverExpr
        ) match {
          case Some(error) => closeWithError(open(), error)
          case None =>
        }
      }
      expect(TokenKind.KeywordYield)
      expression()
      close(mark, TreeKind.Expr.ParYield)
    }

    private def parYieldFragment()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      val mark = open()
      Pattern.pattern()
      expect(TokenKind.ArrowThinL)
      expression()
      close(mark, TreeKind.Expr.ParYieldFragment)
    }

    private def fixpointConstraintSetExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.HashCurlyL))
      val mark = open()
      zeroOrMore(
        namedTokenSet = NamedTokenSet.FromKinds(NAME_PREDICATE),
        checkForItem = NAME_PREDICATE.contains,
        getItem = fixpointConstraint,
        delimiterL = TokenKind.HashCurlyL,
        delimiterR = TokenKind.CurlyR,
        separation = Separation.Required(TokenKind.DotWhiteSpace, allowTrailing = true),
        breakWhen = _.isRecoverExpr,
      )
      close(mark, TreeKind.Expr.FixpointConstraintSet)
    }

    private def fixpointConstraint()(implicit s: State): Mark.Closed = {
      val mark = open()
      Predicate.head()
      if (eat(TokenKind.ColonMinus)) {
        Predicate.body()
        while (eat(TokenKind.Comma) && !eof()) {
          Predicate.body()
        }
      }
      close(mark, TreeKind.Expr.FixpointConstraint)
    }

    private def fixpointLambdaExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.HashParenL))
      val mark = open()
      Predicate.params()
      expect(TokenKind.ArrowThinR)
      expression()
      close(mark, TreeKind.Expr.FixpointLambda)
    }

    private def fixpointSolveExpr(isPSolve: Boolean)(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      val expectedToken = if (isPSolve) TokenKind.KeywordPSolve else TokenKind.KeywordSolve
      assert(at(expectedToken))
      val mark = open()
      expect(expectedToken)
      expression()
      while (eat(TokenKind.Comma) && !eof()) {
        expression()
      }

      if (isPSolve) {
        close(mark, TreeKind.Expr.FixpointSolveWithProvenance)
      } else if (eat(TokenKind.KeywordProject)) {
        nameUnqualified(NAME_PREDICATE)
        while (eat(TokenKind.Comma) && !eof()) {
          nameUnqualified(NAME_PREDICATE)
        }
      }
      close(mark, TreeKind.Expr.FixpointSolveWithProject)
    }

    private def fixpointInjectExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordInject))
      val mark = open()
      expect(TokenKind.KeywordInject)
      expression()
      while (eat(TokenKind.Comma) && !eof()) {
        expression()
      }
      expect(TokenKind.KeywordInto)
      predicateAndArity()
      while (eat(TokenKind.Comma) && !eof()) {
        predicateAndArity()
      }
      close(mark, TreeKind.Expr.FixpointInject)
    }

    private def fixpointPQueryExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordPQuery))
      val mark = open()
      expect(TokenKind.KeywordPQuery)
      expression()
      while (eat(TokenKind.Comma) && !eof()) {
        expression()
      }
      fixpointPQuerySelect()
      fixpointPQueryWith()
      close(mark, TreeKind.Expr.FixpointQueryWithProvenance)
    }

    private def fixpointPQuerySelect()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      val mark = open()
      expect(TokenKind.KeywordSelect)
      Predicate.head()
      close(mark, TreeKind.Expr.FixpointSelect)
    }

    private def fixpointPQueryWith()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      val mark = open()
      expect(TokenKind.KeywordWith)
      nth(0) match {
        case TokenKind.CurlyL => zeroOrMore(
          namedTokenSet = NamedTokenSet.FromKinds(NAME_PREDICATE),
          getItem = () => nameUnqualified(NAME_PREDICATE),
          checkForItem = NAME_PREDICATE.contains,
          breakWhen = _.isRecoverExpr,
          delimiterL = TokenKind.CurlyL,
          delimiterR = TokenKind.CurlyR
        )
        case t => UnexpectedToken(
          expected = NamedTokenSet.FromKinds(Set(TokenKind.CurlyL)),
          actual = Some(t),
          sctx,
          hint = Some("provide a list of predicates"),
          loc = currentSourceLocation()
        )
      }
      close(mark, TreeKind.Expr.FixpointWith)
    }

    private def fixpointQueryExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordQuery))
      val mark = open()
      expect(TokenKind.KeywordQuery)
      expression()
      while (eat(TokenKind.Comma) && !eof()) {
        expression()
      }
      if (at(TokenKind.KeywordSelect)) {
        fixpointQuerySelect()
      }
      if (at(TokenKind.KeywordFrom)) {
        fixpointQueryFrom()
      }
      if (at(TokenKind.KeywordWhere)) {
        fixpointQueryWhere()
      }
      close(mark, TreeKind.Expr.FixpointQuery)
    }

    private def fixpointQuerySelect()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordSelect))
      val mark = open()
      expect(TokenKind.KeywordSelect)
      (nth(0), nth(1)) match {
        case (TokenKind.ParenL, TokenKind.ParenR) => expression()
        case (TokenKind.ParenL, _) => zeroOrMore(
          namedTokenSet = NamedTokenSet.Expression,
          getItem = () => expression(),
          checkForItem = _.isFirstExpr,
          breakWhen = _.isRecoverExpr
        )
        case _ => expression()
      }
      close(mark, TreeKind.Expr.FixpointSelect)
    }

    private def fixpointQueryFrom()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordFrom))
      val mark = open()
      expect(TokenKind.KeywordFrom)
      Predicate.atom()
      while (eat(TokenKind.Comma) && !eof()) {
        Predicate.atom()
      }
      close(mark, TreeKind.Expr.FixpointFromFragment)
    }

    private def fixpointQueryWhere()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(at(TokenKind.KeywordWhere))
      val mark = open()
      expect(TokenKind.KeywordWhere)
      expression()
      close(mark, TreeKind.Expr.FixpointWhere)
    }

    private def predicateAndArity()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      val mark = open()
      nameUnqualified(NAME_PREDICATE)

      val hint = "provide a predicate arity such as: Pred/1"
      // check for shape "/2"
      expect(TokenKind.Slash, hint = Some(hint))
      expect(TokenKind.LiteralInt, hint = Some(hint))

      close(mark, TreeKind.PredicateAndArity)
    }

    private def intrinsicExpr()(implicit s: State): Mark.Closed = {
      val mark = open()
      advance()
      close(mark, TreeKind.Expr.Intrinsic)
    }

    private val FIRST_EXPR_INTERPOLATED_STRING: Set[TokenKind] = Set(TokenKind.LiteralStringInterpolationL, TokenKind.LiteralDebugStringL)

    private def interpolatedStringExpr()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.OtherExpr
      assert(atAny(FIRST_EXPR_INTERPOLATED_STRING))
      val mark = open()

      def atTerminator(kind: Option[TokenKind]) = kind match {
        case Some(TokenKind.LiteralStringInterpolationL) => at(TokenKind.LiteralStringInterpolationR)
        case Some(TokenKind.LiteralDebugStringL) => at(TokenKind.LiteralDebugStringR)
        case _ => false
      }

      def getOpener: Option[TokenKind] = nth(0) match {
        case TokenKind.LiteralStringInterpolationL => advance(); Some(TokenKind.LiteralStringInterpolationL)
        case TokenKind.LiteralDebugStringL => advance(); Some(TokenKind.LiteralDebugStringR)
        case _ => None
      }

      var lastOpener = getOpener
      while (lastOpener.isDefined && !eof()) {
        if (atTerminator(lastOpener)) {
          lastOpener = None // Terminate the loop.
        } else {
          expression()
          lastOpener = getOpener // Try to get nested interpolation.
        }
      }
      expectAny(Set(TokenKind.LiteralStringInterpolationR, TokenKind.LiteralDebugStringR))
      close(mark, TreeKind.Expr.StringInterpolation)
    }
  }

  private object Pattern {
    def pattern()(implicit s: State): Mark.Closed = {
      // If a new pattern is added here then add it to FIRST_PATTERN too.
      val mark = open()
      var lhs = nth(0) match {
        case TokenKind.NameLowerCase
             | TokenKind.NameGreek
             | TokenKind.NameMath
             | TokenKind.Underscore
             | TokenKind.KeywordQuery => variablePat()
        case TokenKind.LiteralString
             | TokenKind.LiteralChar
             | TokenKind.LiteralFloat
             | TokenKind.LiteralFloat32
             | TokenKind.LiteralFloat64
             | TokenKind.LiteralBigDecimal
             | TokenKind.LiteralInt
             | TokenKind.LiteralInt8
             | TokenKind.LiteralInt16
             | TokenKind.LiteralInt32
             | TokenKind.LiteralInt64
             | TokenKind.LiteralBigInt
             | TokenKind.KeywordTrue
             | TokenKind.KeywordFalse
             | TokenKind.LiteralRegex
             | TokenKind.KeywordNull => literalPat()
        case TokenKind.NameUpperCase => tagPat()
        case TokenKind.ParenL => tuplePat()
        case TokenKind.CurlyL => recordPat()
        case TokenKind.Minus => unaryPat()
        case t =>
          val mark = open()
          val error = UnexpectedToken(expected = NamedTokenSet.Pattern, actual = Some(t), loc = currentSourceLocation())
          closeWithError(mark, error)
      }
      // Handle FCons.
      if (eat(TokenKind.ColonColon)) {
        lhs = close(openBefore(lhs), TreeKind.Pattern.Pattern)
        pattern()
        close(openBefore(lhs), TreeKind.Pattern.FCons)
      }
      close(mark, TreeKind.Pattern.Pattern)
    }

    def extPattern()(implicit s: State): Mark.Closed = {
      // If a new pattern is added here then add it to FIRST_PATTERN too.
      val mark = open()
      nth(0) match {
        case TokenKind.NameUpperCase => extTagPat()
        case t =>
          val mark = open()
          val error = UnexpectedToken(expected = NamedTokenSet.Pattern, actual = Some(t), loc = currentSourceLocation())
          closeWithError(mark, error)
      }
      close(mark, TreeKind.Pattern.Pattern)
    }

    private def variablePat()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      val mark = open()
      nameUnqualified(NAME_VARIABLE)
      close(mark, TreeKind.Pattern.Variable)
    }

    private def literalPat()(implicit s: State): Mark.Closed = {
      val mark = open()
      advance()
      close(mark, TreeKind.Pattern.Literal)
    }

    private def tagPat()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      val mark = open()
      nameAllowQualified(NAME_TAG)
      if (at(TokenKind.ParenL)) {
        tuplePat()
      }
      close(mark, TreeKind.Pattern.Tag)
    }

    private def extTagPat()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      val mark = open()
      nameUnqualified(NAME_TAG)
      if (at(TokenKind.ParenL)) {
        tuplePat()
      }
      close(mark, TreeKind.Pattern.ExtTag)
    }

    private def tuplePat()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      assert(at(TokenKind.ParenL))
      val mark = open()
      zeroOrMore(
        namedTokenSet = NamedTokenSet.Pattern,
        getItem = pattern,
        checkForItem = _.isFirstPattern,
        breakWhen = _.isRecoverExpr,
      )
      close(mark, TreeKind.Pattern.Tuple)
    }

    private def recordPat()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      assert(at(TokenKind.CurlyL))
      val mark = open()
      zeroOrMore(
        namedTokenSet = NamedTokenSet.FromKinds(NAME_FIELD),
        getItem = recordField,
        checkForItem = NAME_FIELD.contains,
        delimiterL = TokenKind.CurlyL,
        delimiterR = TokenKind.CurlyR,
        optionallyWith = Some((TokenKind.Bar, () => pattern())),
        breakWhen = _.isRecoverExpr,
      )
      close(mark, TreeKind.Pattern.Record)
    }

    private def recordField()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      val mark = open()
      nameUnqualified(NAME_FIELD)
      if (eat(TokenKind.Equal)) {
        pattern()
      }
      close(mark, TreeKind.Pattern.RecordFieldFragment)
    }

    private def unaryPat()(implicit s: State): Mark.Closed = {
      val mark = open()
      val opMark = open()
      advance()
      close(opMark, TreeKind.Operator)
      advance()
      close(mark, TreeKind.Pattern.Unary)
    }
  }

  private object Type {
    def typeAndEffect()(implicit s: State): Mark.Closed = {
      val lhs = ttype()
      if (eat(TokenKind.Backslash)) {
        val mark = open()
        ttype()
        close(mark, TreeKind.Type.Effect)
      } else lhs
    }

    def ttype(left: TokenKind = TokenKind.Eof)(implicit s: State): Mark.Closed = {
      if (left == TokenKind.ArrowThinR) return typeAndEffect()
      var lhs = typeDelimited()
      // Handle Type argument application.
      while (at(TokenKind.BracketL)) {
        val mark = openBefore(lhs)
        arguments()
        lhs = close(mark, TreeKind.Type.Apply)
        lhs = close(openBefore(lhs), TreeKind.Type.Type)
      }
      // Handle binary operators.
      var continue = true
      while (continue) {
        val right = nth(0)
        if (right == TokenKind.Tilde) {
          // This branch is hit when tilde is used like a binary operator.
          // That only happens in equality constraints, and in that case,
          // we want to let that rule continue instead of producing a binary type expression.
          continue = false
        } else if (rightBindsTighter(left, right)) {
          val mark = openBefore(lhs)
          val markOp = open()
          advance()
          close(markOp, TreeKind.Operator)
          ttype(right)
          lhs = close(mark, TreeKind.Type.Binary)
          lhs = close(openBefore(lhs), TreeKind.Type.Type)
        } else {
          continue = false
        }
      }
      // Handle kind ascriptions.
      if (eat(TokenKind.Colon)) {
        Type.kind()
        lhs = close(openBefore(lhs), TreeKind.Type.Ascribe)
        lhs = close(openBefore(lhs), TreeKind.Type.Type)
      }

      lhs
    }

    /** A precedence table for type operators, lower is higher precedence. */
    private def TYPE_OP_PRECEDENCE: List[Array[TokenKind]] = List(
      // Binary ops.
      Array(TokenKind.ArrowThinR),
      Array(TokenKind.KeywordRvadd, TokenKind.KeywordRvsub),
      Array(TokenKind.KeywordRvand),
      Array(TokenKind.Plus, TokenKind.Minus),
      Array(TokenKind.Ampersand),
      Array(TokenKind.KeywordXor),
      Array(TokenKind.KeywordOr),
      Array(TokenKind.KeywordAnd),
      // Unary ops.
      Array(TokenKind.KeywordRvnot, TokenKind.Tilde, TokenKind.KeywordNot)
    )

    private def rightBindsTighter(left: TokenKind, right: TokenKind): Boolean = {
      val rt = TYPE_OP_PRECEDENCE.indexWhere(l => l.contains(right))
      if (rt == -1) {
        return false
      }

      val lt = TYPE_OP_PRECEDENCE.indexWhere(l => l.contains(left))
      if (lt == -1) {
        assert(left == TokenKind.Eof)
        return true
      }
      // This `>` (instead of `>=`) means that operators with equal precedence are left-associative.
      // E.g. `t + eff1 + eff2` becomes `(t + eff1) + eff2` rather than `t + (eff1 + eff2)`.
      rt > lt
    }

    def arguments()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      val mark = open()
      zeroOrMore(
        namedTokenSet = NamedTokenSet.Type,
        getItem = argument,
        checkForItem = _.isFirstType,
        delimiterL = TokenKind.BracketL,
        delimiterR = TokenKind.BracketR,
        breakWhen = _.isRecoverType,
      )
      close(mark, TreeKind.Type.ArgumentList)
    }

    private def argument()(implicit s: State): Mark.Closed = {
      val mark = open()
      ttype()
      close(mark, TreeKind.Type.Argument)
    }

    def parameters()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      val mark = open()
      zeroOrMore(
        namedTokenSet = NamedTokenSet.Parameter,
        getItem = parameter,
        checkForItem = kind => NAME_VARIABLE.contains(kind),
        delimiterL = TokenKind.BracketL,
        delimiterR = TokenKind.BracketR,
        breakWhen = _.isRecoverType,
      )
      close(mark, TreeKind.TypeParameterList)
    }

    private def parameter()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      val mark = open()
      nameUnqualified(NAME_VARIABLE)
      if (at(TokenKind.Colon)) {
        expect(TokenKind.Colon)
        Type.kind()
      }
      close(mark, TreeKind.Parameter)
    }

    def constraints()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      assert(at(TokenKind.KeywordWith))
      val mark = open()
      expect(TokenKind.KeywordWith)
      // Can't use zeroOrMore here since there's are no delimiterR.
      var continue = true
      while (continue && !eof()) {
        if (atAny(NAME_DEFINITION)) {
          constraint()
        } else {
          val error = UnexpectedToken(expected = NamedTokenSet.FromKinds(NAME_DEFINITION), actual = Some(nth(0)), loc = currentSourceLocation())
          closeWithError(open(), error)
          continue = false
        }
        if (!eat(TokenKind.Comma)) {
          continue = false
        }
      }
      close(mark, TreeKind.Type.ConstraintList)
    }

    private def constraint()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      val mark = open()
      nameAllowQualified(NAME_DEFINITION)
      expect(TokenKind.BracketL)
      Type.ttype()
      expect(TokenKind.BracketR)
      close(mark, TreeKind.Type.Constraint)
    }

    def derivations()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      assert(at(TokenKind.KeywordWith))
      val mark = open()
      expect(TokenKind.KeywordWith)
      // Can't use zeroOrMore here since there's are no delimiterR.
      var continue = true
      while (continue && !eof()) {
        if (atAny(NAME_QNAME)) {
          nameAllowQualified(NAME_QNAME)
        } else {
          val error = UnexpectedToken(expected = NamedTokenSet.FromKinds(NAME_QNAME), actual = Some(nth(0)), loc = currentSourceLocation())
          closeWithError(open(), error)
          continue = false
        }
        if (!eat(TokenKind.Comma)) {
          continue = false
        }
      }
      close(mark, TreeKind.DerivationList)
    }

    private def typeDelimited()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      // If a new type is added here then add it to TYPE_FIRST too.
      val mark = open()
      nth(0) match {
        case TokenKind.NameUpperCase => nameAllowQualified(NAME_TYPE)
        case TokenKind.NameMath
             | TokenKind.NameGreek
             | TokenKind.Underscore => nameUnqualified(NAME_VARIABLE)
        case TokenKind.NameLowerCase => variableType()
        case TokenKind.KeywordUniv
             | TokenKind.KeywordFalse
             | TokenKind.KeywordTrue => constantType()
        case TokenKind.ParenL => tupleOrRecordRowType()
        case TokenKind.CurlyL => recordOrEffectSetType()
        case TokenKind.HashBar => extensibleType()
        case TokenKind.HashCurlyL => schemaType()
        case TokenKind.HashParenL => schemaRowType()
        case TokenKind.AngleL => caseSetType()
        case TokenKind.KeywordNot
             | TokenKind.Tilde
             | TokenKind.KeywordRvnot => unaryType()
        // TODO: Static is used as a type name in Prelude.flix. That requires special handling here.
        // If this rule is removed, remove KeywordStaticUppercase from FIRST_TYPE too.
        case TokenKind.KeywordStaticUppercase => nameUnqualified(Set(TokenKind.KeywordStaticUppercase))
        case t =>
          val mark = open()
          val error = UnexpectedToken(expected = NamedTokenSet.Type, actual = Some(t), loc = currentSourceLocation())
          closeWithError(mark, error)
      }
      close(mark, TreeKind.Type.Type)
    }

    private val TYPE_VAR: Set[TokenKind] = Set(TokenKind.NameLowerCase, TokenKind.NameGreek, TokenKind.NameMath, TokenKind.Underscore)

    private def variableType()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      val mark = open()
      expectAny(TYPE_VAR)
      close(mark, TreeKind.Type.Variable)
    }

    private val TYPE_CONSTANT: Set[TokenKind] = Set(TokenKind.KeywordUniv, TokenKind.KeywordFalse, TokenKind.KeywordTrue)

    private def constantType()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      val mark = open()
      expectAny(TYPE_CONSTANT)
      close(mark, TreeKind.Type.Constant)
    }

    private def tupleOrRecordRowType()(implicit s: State): Mark.Closed = {
      assert(at(TokenKind.ParenL))
      // Record rows follow the rule `'(' (name '=' type)* ('|' name)? ')'`.
      // The prefix is always `( name '='" or "'|'`.
      val next = nth(1)
      val nextnext = nth(2)
      val isRecordRow = next == TokenKind.Bar || next == TokenKind.NameLowerCase && nextnext == TokenKind.Equal || next == TokenKind.ParenR
      if (isRecordRow) {
        recordRow()
      } else {
        tuple()
      }
    }

    private def recordRow()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      assert(at(TokenKind.ParenL))
      val mark = open()
      zeroOrMore(
        namedTokenSet = NamedTokenSet.FromKinds(NAME_FIELD),
        getItem = recordField,
        checkForItem = NAME_FIELD.contains,
        breakWhen = _.isRecoverType,
        optionallyWith = Some((TokenKind.Bar, variableType)),
      )
      close(mark, TreeKind.Type.RecordRow)
    }

    private def tuple()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      assert(at(TokenKind.ParenL))
      val mark = open()
      zeroOrMore(
        namedTokenSet = NamedTokenSet.Type,
        getItem = () => ttype(),
        checkForItem = _.isFirstType,
        breakWhen = _.isRecoverType,
      )
      close(mark, TreeKind.Type.Tuple)
    }

    private def recordOrEffectSetType()(implicit s: State): Mark.Closed = {
      assert(at(TokenKind.CurlyL))
      val isRecord = (nth(1), nth(2)) match {
        case (TokenKind.CurlyR, _) => false
        case (TokenKind.Bar, _)
             | (_, TokenKind.Bar)
             | (_, TokenKind.Equal) => true
        case _ => false
      }
      if (isRecord) record() else effectSet()
    }

    private def record()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      assert(at(TokenKind.CurlyL))
      val mark = open()
      (nth(1), nth(2)) match {
        // Empty record type.
        case (TokenKind.Bar, TokenKind.CurlyR) =>
          advance() // Consume '{'.
          advance() // Consume '|'.
          advance() // Consume '}'.
          close(mark, TreeKind.Type.Record)
        case _ =>
          zeroOrMore(
            namedTokenSet = NamedTokenSet.FromKinds(NAME_FIELD),
            getItem = recordField,
            checkForItem = NAME_FIELD.contains,
            breakWhen = _.isRecoverType,
            delimiterL = TokenKind.CurlyL,
            delimiterR = TokenKind.CurlyR,
            optionallyWith = Some((TokenKind.Bar, variableType)),
          )
          close(mark, TreeKind.Type.Record)
      }
    }

    private def recordField()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      val mark = open()
      nameUnqualified(NAME_FIELD)
      expect(TokenKind.Equal)
      ttype()
      close(mark, TreeKind.Type.RecordFieldFragment)
    }

    private def effectSet()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      val mark = open()
      zeroOrMore(
        namedTokenSet = NamedTokenSet.Effect,
        getItem = () => ttype(),
        checkForItem = _.isFirstType,
        breakWhen = _.isRecoverType,
        delimiterL = TokenKind.CurlyL,
        delimiterR = TokenKind.CurlyR,
      )
      close(mark, TreeKind.Type.EffectSet)
    }

    private def extensibleType()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      assert(at(TokenKind.HashBar))
      val mark = open()
      zeroOrMore(
        namedTokenSet = NamedTokenSet.FromKinds(NAME_PREDICATE),
        getItem = schemaTerm,
        checkForItem = NAME_PREDICATE.contains,
        delimiterL = TokenKind.HashBar,
        delimiterR = TokenKind.BarHash,
        breakWhen = _.isRecoverType,
        optionallyWith = Some((TokenKind.Bar, () => nameUnqualified(NAME_VARIABLE))),
      )
      close(mark, TreeKind.Type.Extensible)
    }

    private def schemaType()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      assert(at(TokenKind.HashCurlyL))
      val mark = open()
      zeroOrMore(
        namedTokenSet = NamedTokenSet.FromKinds(NAME_PREDICATE),
        getItem = schemaTerm,
        checkForItem = NAME_PREDICATE.contains,
        delimiterL = TokenKind.HashCurlyL,
        delimiterR = TokenKind.CurlyR,
        breakWhen = _.isRecoverType,
        optionallyWith = Some((TokenKind.Bar, () => nameUnqualified(NAME_VARIABLE))),
      )
      close(mark, TreeKind.Type.Schema)
    }

    private def schemaRowType()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      assert(at(TokenKind.HashParenL))
      val mark = open()
      zeroOrMore(
        namedTokenSet = NamedTokenSet.FromKinds(NAME_PREDICATE),
        getItem = schemaTerm,
        checkForItem = NAME_PREDICATE.contains,
        delimiterL = TokenKind.HashParenL,
        breakWhen = _.isRecoverType,
        optionallyWith = Some((TokenKind.Bar, () => nameUnqualified(NAME_VARIABLE))),
      )
      close(mark, TreeKind.Type.SchemaRow)
    }

    private def schemaTerm()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      val mark = open()
      nameAllowQualified(NAME_PREDICATE)
      if (at(TokenKind.BracketL)) {
        arguments()
        close(mark, TreeKind.Type.PredicateWithAlias)
      } else {
        zeroOrMore(
          namedTokenSet = NamedTokenSet.Type,
          getItem = () => ttype(),
          checkForItem = _.isFirstType,
          breakWhen = _.isRecoverType,
          optionallyWith = Some((TokenKind.Semi, () => {
            val mark = open()
            ttype()
            close(mark, TreeKind.Predicate.LatticeTerm)
          }))
        )
        close(mark, TreeKind.Type.PredicateWithTypes)
      }
    }

    private def caseSetType()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      assert(at(TokenKind.AngleL))
      val mark = open()
      zeroOrMore(
        namedTokenSet = NamedTokenSet.FromKinds(NAME_DEFINITION),
        getItem = () => nameAllowQualified(NAME_DEFINITION),
        checkForItem = NAME_DEFINITION.contains,
        breakWhen = _.isRecoverType,
        delimiterL = TokenKind.AngleL,
        delimiterR = TokenKind.AngleR,
      )
      close(mark, TreeKind.Type.CaseSet)
    }

    private val FIRST_TYPE_UNARY: Set[TokenKind] = Set(TokenKind.Tilde, TokenKind.KeywordNot, TokenKind.KeywordRvnot)

    private def unaryType()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown
      val mark = open()
      val op = nth(0)
      val markOp = open()
      expectAny(FIRST_TYPE_UNARY)
      close(markOp, TreeKind.Operator)
      ttype(left = op)
      close(mark, TreeKind.Type.Unary)
    }

    def kind()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Unknown

      def kindFragment(): Unit = {
        val inParens = eat(TokenKind.ParenL)
        nameUnqualified(NAME_KIND)
        // Check for arrow kind.
        if (eat(TokenKind.ArrowThinR)) {
          kind()
        }
        // Consume ')' if necessary.
        if (inParens) {
          expect(TokenKind.ParenR)
        }
      }

      val mark = open()
      kindFragment()
      close(mark, TreeKind.Kind)
    }
  }

  private object Predicate {
    def head()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.Constraint
      val mark = open()
      nameUnqualified(NAME_PREDICATE)
      termList()
      close(mark, TreeKind.Predicate.Head)
    }

    private def termList()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.Constraint
      val mark = open()
      // Check for missing term list.
      if (!at(TokenKind.ParenL)) {
        closeWithError(open(), UnexpectedToken(
          expected = NamedTokenSet.FromKinds(Set(TokenKind.ParenL)),
          actual = Some(nth(0)),
          sctx = sctx,
          hint = Some("provide a list of terms."),
          loc = previousSourceLocation())
        )
      }

      zeroOrMore(
        namedTokenSet = NamedTokenSet.Expression,
        getItem = () => Expr.expression(),
        checkForItem = _.isFirstExpr,
        breakWhen = _.isRecoverExpr,
        optionallyWith = Some((TokenKind.Semi, () => {
          val mark = open()
          Expr.expression()
          close(mark, TreeKind.Predicate.LatticeTerm)
        }))
      )
      close(mark, TreeKind.Predicate.TermList)
    }

    private def patternList()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.Constraint
      val mark = open()
      zeroOrMore(
        namedTokenSet = NamedTokenSet.Pattern,
        getItem = Pattern.pattern,
        checkForItem = _.isFirstPattern,
        breakWhen = _.isRecoverPattern,
        optionallyWith = Some((TokenKind.Semi, () => {
          val mark = open()
          Pattern.pattern()
          close(mark, TreeKind.Predicate.LatticeTerm)
        }))
      )
      close(mark, TreeKind.Predicate.PatternList)
    }

    def body()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.Constraint
      val mark = open()
      nth(0) match {
        case TokenKind.KeywordIf =>
          guard()
          close(mark, TreeKind.Predicate.Body)
        case TokenKind.KeywordLet =>
          functional()
          close(mark, TreeKind.Predicate.Body)
        case TokenKind.KeywordNot | TokenKind.KeywordFix | TokenKind.NameUpperCase =>
          atom()
          close(mark, TreeKind.Predicate.Body)
        case at =>
          val error = UnexpectedToken(
            expected = NamedTokenSet.FixpointConstraint,
            actual = Some(at),
            sctx,
            loc = currentSourceLocation()
          )
          closeWithError(mark, error)
      }
    }

    private def guard()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.Constraint
      assert(at(TokenKind.KeywordIf))
      val mark = open()
      expect(TokenKind.KeywordIf)
      expect(TokenKind.ParenL)
      Expr.expression()
      expect(TokenKind.ParenR)
      close(mark, TreeKind.Predicate.Guard)
    }

    private def functional()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.Constraint
      assert(at(TokenKind.KeywordLet))
      val mark = open()
      expect(TokenKind.KeywordLet)
      nth(0) match {
        case TokenKind.ParenL => zeroOrMore(
          namedTokenSet = NamedTokenSet.FromKinds(NAME_VARIABLE),
          getItem = () => nameUnqualified(NAME_VARIABLE),
          checkForItem = NAME_VARIABLE.contains,
          breakWhen = kind => kind == TokenKind.Equal || kind.isFirstExpr
        )
        case TokenKind.NameLowerCase
             | TokenKind.NameMath
             | TokenKind.NameGreek
             | TokenKind.Underscore => nameUnqualified(NAME_VARIABLE)
        case at =>
          val error = UnexpectedToken(
            expected = NamedTokenSet.FromKinds(Set(TokenKind.ParenL, TokenKind.NameLowerCase, TokenKind.NameMath, TokenKind.NameGreek, TokenKind.Underscore)),
            actual = Some(at),
            sctx,
            loc = currentSourceLocation()
          )
          advanceWithError(error)
      }
      expect(TokenKind.Equal)
      Expr.expression()
      close(mark, TreeKind.Predicate.Functional)
    }

    def atom()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.Constraint
      val mark = open()
      eat(TokenKind.KeywordNot)
      eat(TokenKind.KeywordFix)
      nameUnqualified(NAME_PREDICATE)
      patternList()
      close(mark, TreeKind.Predicate.Atom)
    }

    def params()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.Constraint
      val mark = open()
      zeroOrMore(
        namedTokenSet = NamedTokenSet.Parameter,
        getItem = Predicate.param,
        checkForItem = NAME_PREDICATE.contains,
        delimiterL = TokenKind.HashParenL,
        breakWhen = kind => kind == TokenKind.ArrowThinR || kind.isFirstExpr
      )
      close(mark, TreeKind.Predicate.ParamList)
    }

    private def param()(implicit s: State): Mark.Closed = {
      implicit val sctx: SyntacticContext = SyntacticContext.Expr.Constraint
      var kind: TreeKind = TreeKind.Predicate.ParamUntyped
      val mark = open()
      nameUnqualified(NAME_PREDICATE)
      kind = TreeKind.Predicate.Param
      zeroOrMore(
        namedTokenSet = NamedTokenSet.Type,
        getItem = () => Type.ttype(),
        checkForItem = _.isFirstType,
        breakWhen = _.isRecoverType,
        optionallyWith = Some((TokenKind.Semi, () => {
          val mark = open()
          Type.ttype()
          close(mark, TreeKind.Predicate.LatticeTerm)
        }))
      )
      close(mark, kind)
    }
  }
}
