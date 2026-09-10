package mechanoid.macros

import scala.quoted.*
import mechanoid.core.alias
import mechanoid.persistence.{Alias, AliasCodec, AliasExtractor}
import zio.Chunk

/** Compile-time derivation of [[AliasExtractor]] from `@alias` constructor parameters. */
object AliasExtractorMacros:

  inline def derived[S]: AliasExtractor[S] = ${ derivedImpl[S] }

  private def derivedImpl[S: Type](using Quotes): Expr[AliasExtractor[S]] =
    import quotes.reflect.*

    val tpe      = TypeRepr.of[S]
    val sym      = tpe.typeSymbol
    val aliasSym = TypeRepr.of[alias].typeSymbol

    def namespaceOf(param: Symbol): Option[String] =
      param.getAnnotation(aliasSym).map { annot =>
        val fromArgs = annot match
          case Apply(_, args) =>
            args.collectFirst {
              case Literal(StringConstant(s))              => s
              case NamedArg(_, Literal(StringConstant(s))) => s
            }
          case _ => None
        val ns = fromArgs.getOrElse("")
        if ns.isEmpty then param.name else ns
      }

    def encodeField(owner: Expr[Any], param: Symbol): Option[Expr[Chunk[Alias]]] =
      namespaceOf(param).map { ns =>
        val select     = Select.unique(owner.asTerm, param.name)
        val fieldTpe   = select.tpe.widen.dealias
        val selectExpr = select.asExpr
        val nsExpr     = Expr(ns)
        fieldTpe.asType match
          case '[String] =>
            // String is Iterable[Char]; treat as a single key.
            '{
              val codec = summon[AliasCodec[String]]
              Chunk(Alias($nsExpr, codec.encode(${ selectExpr.asExprOf[String] })))
            }
          case '[Option[t]] =>
            Expr.summon[AliasCodec[t]] match
              case Some(codec) =>
                '{
                  Chunk.fromIterable(
                    ${ selectExpr.asExprOf[Option[t]] }.map(v => Alias($nsExpr, $codec.encode(v)))
                  )
                }
              case None =>
                report.errorAndAbort(
                  s"No AliasCodec available for ${Type.show[t]} on @alias field ${param.name}"
                )
          case '[Array[t]] =>
            Expr.summon[AliasCodec[t]] match
              case Some(codec) =>
                '{
                  Chunk.fromIterable(
                    ${ selectExpr.asExprOf[Array[t]] }.iterator.map(v => Alias($nsExpr, $codec.encode(v))).toList
                  )
                }
              case None =>
                report.errorAndAbort(
                  s"No AliasCodec available for ${Type.show[t]} on @alias field ${param.name}"
                )
          case '[Iterable[t]] =>
            Expr.summon[AliasCodec[t]] match
              case Some(codec) =>
                '{
                  Chunk.fromIterable(
                    ${ selectExpr.asExprOf[Iterable[t]] }.map(v => Alias($nsExpr, $codec.encode(v)))
                  )
                }
              case None =>
                report.errorAndAbort(
                  s"No AliasCodec available for ${Type.show[t]} on @alias field ${param.name}"
                )
          case '[t] =>
            Expr.summon[AliasCodec[t]] match
              case Some(codec) =>
                '{ Chunk(Alias($nsExpr, $codec.encode(${ selectExpr.asExprOf[t] }))) }
              case None =>
                report.errorAndAbort(
                  s"No AliasCodec available for ${Type.show[t]} on @alias field ${param.name}"
                )
        end match
      }

    def fieldsOf(caseSym: Symbol, instance: Expr[Any]): Expr[Chunk[Alias]] =
      val params = caseSym.primaryConstructor.paramSymss.headOption.getOrElse(Nil)
      val parts  = params.flatMap(encodeField(instance, _))
      if parts.isEmpty then '{ Chunk.empty[Alias] } else parts.reduce { (a, b) => '{ $a ++ $b } }

    val extractorExpr: Expr[AliasExtractor[S]] =
      if sym.flags.is(Flags.Enum) || (sym.flags.is(Flags.Sealed) && sym.children.nonEmpty) then
        val cases = findLeafCases(sym)
        if cases.isEmpty then '{ AliasExtractor.none[S] }
        else
          val matchCases: List[CaseDef] = cases.map { caseSym =>
            val isModule = caseSym.flags.is(Flags.Module) || !caseSym.isClassDef
            if isModule then
              val module = if caseSym.flags.is(Flags.Module) then caseSym.companionModule else caseSym
              CaseDef(Ident(module.termRef), None, '{ Chunk.empty[Alias] }.asTerm)
            else
              val bindName = Symbol.newBind(Symbol.spliceOwner, "x", Flags.EmptyFlags, caseSym.typeRef)
              val bindPat  = Bind(bindName, Typed(Wildcard(), Inferred(caseSym.typeRef)))
              val rhs      = fieldsOf(caseSym, Ref(bindName).asExpr)
              CaseDef(bindPat, None, rhs.asTerm)
          }
          val fallback = CaseDef(Wildcard(), None, '{ Chunk.empty[Alias] }.asTerm)
          '{
            new AliasExtractor[S]:
              def aliases(state: S): Chunk[Alias] =
                ${ Match('state.asTerm, matchCases :+ fallback).asExprOf[Chunk[Alias]] }
          }
        end if
      else if tpe <:< TypeRepr.of[Product] || sym.flags.is(Flags.Case) then
        '{
          new AliasExtractor[S]:
            def aliases(state: S): Chunk[Alias] =
              ${ fieldsOf(sym, 'state.asExprOf[Any]) }
        }
      else '{ AliasExtractor.none[S] }

    extractorExpr
  end derivedImpl

  private def findLeafCases(using Quotes)(sym: quotes.reflect.Symbol): List[quotes.reflect.Symbol] =
    import quotes.reflect.*
    sym.children.flatMap { child =>
      if child.flags.is(Flags.Sealed) then findLeafCases(child)
      else if child.flags.is(Flags.Case) || child.flags.is(Flags.Enum) || child.isClassDef then List(child)
      else Nil
    }
end AliasExtractorMacros
