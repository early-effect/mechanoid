package mechanoid.macros

import java.time.Instant
import scala.quoted.*
import mechanoid.core.{alias, index, indexCreated, indexRank, indexUpdated}
import mechanoid.persistence.{AliasCodec, IndexClocks, IndexExtractor, IndexKey}
import zio.Chunk

/** Compile-time derivation of [[IndexExtractor]] from `@index` / `@indexCreated` / `@indexUpdated`. */
object IndexExtractorMacros:

  inline def derived[S]: IndexExtractor[S] = ${ derivedImpl[S] }

  private def derivedImpl[S: Type](using Quotes): Expr[IndexExtractor[S]] =
    import quotes.reflect.*

    val tpe        = TypeRepr.of[S]
    val sym        = tpe.typeSymbol
    val indexSym   = TypeRepr.of[index].typeSymbol
    val aliasSym   = TypeRepr.of[alias].typeSymbol
    val createdSym = TypeRepr.of[indexCreated].typeSymbol
    val updatedSym = TypeRepr.of[indexUpdated].typeSymbol
    val rankSym    = TypeRepr.of[indexRank].typeSymbol
    val instantTpe = TypeRepr.of[Instant]

    def namespaceOf(param: Symbol): Option[String] =
      if param.hasAnnotation(aliasSym) && param.hasAnnotation(indexSym) then
        report.errorAndAbort(
          s"Field ${param.name} cannot have both @alias and @index"
        )
      if param.hasAnnotation(indexSym) then Some(param.name) else None
    end namespaceOf

    def encodeField(owner: Expr[Any], param: Symbol): Option[Expr[Chunk[IndexKey]]] =
      namespaceOf(param).map { ns =>
        val select     = Select.unique(owner.asTerm, param.name)
        val fieldTpe   = select.tpe.widen.dealias
        val selectExpr = select.asExpr
        val nsExpr     = Expr(ns)
        fieldTpe.asType match
          case '[String] =>
            '{
              val codec = summon[AliasCodec[String]]
              Chunk(IndexKey($nsExpr, codec.encode(${ selectExpr.asExprOf[String] })))
            }
          case '[Option[t]] =>
            Expr.summon[AliasCodec[t]] match
              case Some(codec) =>
                '{
                  Chunk.fromIterable(
                    ${ selectExpr.asExprOf[Option[t]] }.map(v => IndexKey($nsExpr, $codec.encode(v)))
                  )
                }
              case None =>
                report.errorAndAbort(
                  s"No AliasCodec available for ${Type.show[t]} on @index field ${param.name}"
                )
          case '[Array[t]] =>
            Expr.summon[AliasCodec[t]] match
              case Some(codec) =>
                '{
                  Chunk.fromIterable(
                    ${ selectExpr.asExprOf[Array[t]] }.iterator
                      .map(v => IndexKey($nsExpr, $codec.encode(v)))
                      .toList
                  )
                }
              case None =>
                report.errorAndAbort(
                  s"No AliasCodec available for ${Type.show[t]} on @index field ${param.name}"
                )
          case '[Iterable[t]] =>
            Expr.summon[AliasCodec[t]] match
              case Some(codec) =>
                '{
                  Chunk.fromIterable(
                    ${ selectExpr.asExprOf[Iterable[t]] }.map(v => IndexKey($nsExpr, $codec.encode(v)))
                  )
                }
              case None =>
                report.errorAndAbort(
                  s"No AliasCodec available for ${Type.show[t]} on @index field ${param.name}"
                )
          case '[t] =>
            Expr.summon[AliasCodec[t]] match
              case Some(codec) =>
                '{ Chunk(IndexKey($nsExpr, $codec.encode(${ selectExpr.asExprOf[t] }))) }
              case None =>
                report.errorAndAbort(
                  s"No AliasCodec available for ${Type.show[t]} on @index field ${param.name}"
                )
        end match
      }

    def instantSelect(owner: Expr[Any], param: Symbol): Expr[Instant] =
      val fieldTpe = Select.unique(owner.asTerm, param.name).tpe.widen.dealias
      if !(fieldTpe =:= instantTpe) then
        report.errorAndAbort(
          s"@indexCreated / @indexUpdated field ${param.name} must be java.time.Instant, got ${fieldTpe.show}"
        )
      Select.unique(owner.asTerm, param.name).asExprOf[Instant]

    def clocksOf(caseSym: Symbol, instance: Expr[Any]): Expr[Option[IndexClocks]] =
      val params  = caseSym.primaryConstructor.paramSymss.headOption.getOrElse(Nil)
      val created = params.filter(_.hasAnnotation(createdSym))
      val updated = params.filter(_.hasAnnotation(updatedSym))
      if created.size > 1 then report.errorAndAbort(s"${caseSym.name} has more than one @indexCreated field")
      if updated.size > 1 then report.errorAndAbort(s"${caseSym.name} has more than one @indexUpdated field")
      (created.headOption, updated.headOption) match
        case (None, None) =>
          '{ None }
        case (c, u) =>
          val createdExpr: Expr[Option[Instant]] = c match
            case Some(p) => '{ Some(${ instantSelect(instance, p) }) }
            case None    => '{ None }
          val updatedExpr: Expr[Option[Instant]] = u match
            case Some(p) => '{ Some(${ instantSelect(instance, p) }) }
            case None    => '{ None }
          '{ Some(IndexClocks($createdExpr, $updatedExpr)) }
      end match
    end clocksOf

    def rankOf(caseSym: Symbol, instance: Expr[Any]): Expr[Option[Long]] =
      val params = caseSym.primaryConstructor.paramSymss.headOption.getOrElse(Nil)
      val ranks  = params.filter(_.hasAnnotation(rankSym))
      if ranks.size > 1 then report.errorAndAbort(s"${caseSym.name} has more than one @indexRank field")
      ranks.headOption match
        case None    => '{ None }
        case Some(p) =>
          val select   = Select.unique(instance.asTerm, p.name)
          val fieldTpe = select.tpe.widen.dealias
          fieldTpe.asType match
            case '[Int]   => '{ Some(${ select.asExprOf[Int] }.toLong) }
            case '[Long]  => '{ Some(${ select.asExprOf[Long] }) }
            case '[Short] => '{ Some(${ select.asExprOf[Short] }.toLong) }
            case _        =>
              report.errorAndAbort(s"@indexRank field ${p.name} must be Int, Long, or Short")
      end match
    end rankOf

    def keysOf(caseSym: Symbol, instance: Expr[Any]): Expr[Chunk[IndexKey]] =
      val params = caseSym.primaryConstructor.paramSymss.headOption.getOrElse(Nil)
      val parts  = params.flatMap(encodeField(instance, _))
      if parts.isEmpty then '{ Chunk.empty[IndexKey] } else parts.reduce { (a, b) => '{ $a ++ $b } }

    def leafCase(caseSym: Symbol, bindPrefix: String): CaseDef =
      val isModule = caseSym.flags.is(Flags.Module) || !caseSym.isClassDef
      if isModule then
        val module = if caseSym.flags.is(Flags.Module) then caseSym.companionModule else caseSym
        val rhs    =
          if bindPrefix == "k" then '{ Chunk.empty[IndexKey] }.asTerm
          else '{ None }.asTerm
        CaseDef(Ident(module.termRef), None, rhs)
      else
        val bindName = Symbol.newBind(Symbol.spliceOwner, bindPrefix + caseSym.name, Flags.EmptyFlags, caseSym.typeRef)
        val bindPat  = Bind(bindName, Typed(Wildcard(), Inferred(caseSym.typeRef)))
        val inst     = Ref(bindName).asExpr
        val rhs      =
          if bindPrefix == "k" then keysOf(caseSym, inst).asTerm
          else if bindPrefix == "c" then clocksOf(caseSym, inst).asTerm
          else rankOf(caseSym, inst).asTerm
        CaseDef(bindPat, None, rhs)
      end if
    end leafCase

    val extractorExpr: Expr[IndexExtractor[S]] =
      if sym.flags.is(Flags.Enum) || (sym.flags.is(Flags.Sealed) && sym.children.nonEmpty) then
        val cases = findLeafCases(sym)
        if cases.isEmpty then '{ IndexExtractor.none[S] }
        else
          val keyCases                           = cases.map(leafCase(_, "k"))
          val clockCases                         = cases.map(leafCase(_, "c"))
          val rankCases                          = cases.map(leafCase(_, "r"))
          val keyFallback                        = CaseDef(Wildcard(), None, '{ Chunk.empty[IndexKey] }.asTerm)
          val noneFallback                       = CaseDef(Wildcard(), None, '{ None }.asTerm)
          val keysFn: Expr[S => Chunk[IndexKey]] =
            '{ (st: S) => ${ Match('st.asTerm, keyCases :+ keyFallback).asExprOf[Chunk[IndexKey]] } }
          val clocksFn: Expr[S => Option[IndexClocks]] =
            '{ (st: S) => ${ Match('st.asTerm, clockCases :+ noneFallback).asExprOf[Option[IndexClocks]] } }
          val rankFn: Expr[S => Option[Long]] =
            '{ (st: S) => ${ Match('st.asTerm, rankCases :+ noneFallback).asExprOf[Option[Long]] } }
          '{ IndexExtractor.apply[S]($keysFn, $clocksFn, $rankFn) }
        end if
      else if tpe <:< TypeRepr.of[Product] || sym.flags.is(Flags.Case) then
        val keysFn: Expr[S => Chunk[IndexKey]] =
          '{ (st: S) => ${ keysOf(sym, 'st.asExprOf[Any]) } }
        val clocksFn: Expr[S => Option[IndexClocks]] =
          '{ (st: S) => ${ clocksOf(sym, 'st.asExprOf[Any]) } }
        val rankFn: Expr[S => Option[Long]] =
          '{ (st: S) => ${ rankOf(sym, 'st.asExprOf[Any]) } }
        '{ IndexExtractor.apply[S]($keysFn, $clocksFn, $rankFn) }
      else '{ IndexExtractor.none[S] }

    extractorExpr
  end derivedImpl

  private def findLeafCases(using Quotes)(sym: quotes.reflect.Symbol): List[quotes.reflect.Symbol] =
    import quotes.reflect.*
    sym.children.flatMap { child =>
      if child.flags.is(Flags.Sealed) then findLeafCases(child)
      else if child.flags.is(Flags.Case) || child.flags.is(Flags.Enum) || child.isClassDef then List(child)
      else Nil
    }
end IndexExtractorMacros
