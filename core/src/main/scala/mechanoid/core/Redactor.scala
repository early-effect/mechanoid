package mechanoid.core

import scala.quoted.*
import scala.annotation.unused

/** Typeclass for redacting sensitive information from values.
  *
  * Fields marked with `@sensitive` are automatically redacted - no explicit derivation needed.
  *
  * Usage:
  * {{{
  * enum MyCommand:
  *   case ProcessPayment(
  *     orderId: Int,
  *     @sensitive customerName: String,  // Will show as "customerName={redacted}"
  *     amount: BigDecimal,               // Will show actual value
  *   )
  *
  * import mechanoid.*
  * val cmd: MyCommand = ...
  * println(cmd.redacted)       // ProcessPayment(orderId=1, customerName={redacted}, amount=250.0)
  * println(cmd.redactedPretty) // Multi-line formatted output
  * }}}
  */
trait Redactor[T]:
  /** Returns a redacted string representation of the value (compact, single line). */
  def redact(value: T): String

  /** Returns a pretty-printed redacted string with indentation (multi-line for products). */
  def redactPretty(value: T, @unused indent: Int = 2): String =
    // Default implementation - subclasses can override for better formatting
    redact(value)
end Redactor

object Redactor:

  /** Summon a Redactor instance for type T. */
  def apply[T](using r: Redactor[T]): Redactor[T] = r

  /** Extension method to call redact on any value with a Redactor instance (compact format). */
  extension [T](value: T)(using redactor: Redactor[T]) def redacted: String = redactor.redact(value)

  /** Extension method to call redactPretty on any value with a Redactor instance (multi-line format). */
  extension [T](value: T)(using redactor: Redactor[T])
    def redactedPretty: String              = redactor.redactPretty(value)
    def redactedPretty(indent: Int): String = redactor.redactPretty(value, indent)

  /** Create a Redactor from a function. */
  def instance[T](f: T => String): Redactor[T] = new Redactor[T]:
    def redact(value: T): String = f(value)

  /** Create a Redactor that shows only the class name (fully redacted).
    *
    * Example output: `ProcessPayment({redacted})`
    */
  def redactAll[T <: Product]: Redactor[T] = new Redactor[T]:
    def redact(value: T): String = s"${value.productPrefix}({redacted})"

  // ============================================
  // Built-in instances for common types
  // ============================================

  /** Strings are fully redacted (conservative default for raw string values). */
  given Redactor[String] = instance(_ => "{redacted}")

  /** Automatic redaction for any type - detects @sensitive annotations at compile time.
    *
    * For Product types (case classes, enum cases), fields with @sensitive are redacted. For other types, uses toString.
    */
  inline given auto[T]: Redactor[T] = ${ autoImpl[T] }

  private def autoImpl[T: Type](using Quotes): Expr[Redactor[T]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val sym = tpe.typeSymbol

    // Check if this is a sum type (enum) or product type (case class)
    if sym.flags.is(Flags.Enum) || sym.children.nonEmpty then
      // Sum type - generate match expression for each case
      sumImpl[T](sym)
    else if sym.flags.is(Flags.Case) || tpe <:< TypeRepr.of[Product] then
      // Product type - handle directly
      productImpl[T](sym)
    else
      // Non-product type - just use toString
      '{ instance[T](_.toString) }
  end autoImpl

  private def sumImpl[T: Type](sym: Quotes#reflectModule#Symbol)(using Quotes): Expr[Redactor[T]] =
    import quotes.reflect.*

    val typedSym          = sym.asInstanceOf[Symbol]
    val sensitiveAnnotSym = TypeRepr.of[sensitive].typeSymbol

    // Get all case classes (children of the enum)
    val cases = typedSym.children.filter(_.isClassDef)

    // Build a map of case name -> list of (fieldName, isSensitive)
    val caseFieldInfo: Map[String, List[(String, Boolean)]] = cases.map { caseSym =>
      val params = caseSym.primaryConstructor.paramSymss.headOption.getOrElse(Nil)
      val fields = params.map { param =>
        (param.name, param.hasAnnotation(sensitiveAnnotSym))
      }
      (caseSym.name, fields)
    }.toMap

    val caseFieldInfoExpr: Expr[Map[String, List[(String, Boolean)]]] = Expr(caseFieldInfo)

    '{
      new Redactor[T]:
        private val fieldInfo = $caseFieldInfoExpr

        def redact(value: T): String = formatProduct(value, ", ", "")

        override def redactPretty(value: T, indent: Int): String =
          val pad = " " * indent
          formatProduct(value, s",\n$pad", pad)

        private def formatProduct(value: T, sep: String, initialPad: String): String =
          value match
            case p: Product =>
              val prefix    = p.productPrefix
              val fieldsOpt = fieldInfo.get(prefix)

              fieldsOpt match
                case Some(fields) if fields.nonEmpty =>
                  val fieldStrs = fields.zipWithIndex.map { case ((name, isSensitive), idx) =>
                    if isSensitive then s"$name={redacted}"
                    else s"$name=${p.productElement(idx)}"
                  }
                  if sep.contains("\n") then s"$prefix(\n$initialPad${fieldStrs.mkString(sep)}\n)"
                  else s"$prefix(${fieldStrs.mkString(sep)})"
                case _ =>
                  if p.productArity == 0 then prefix
                  else
                    val elems = (0 until p.productArity).map(i => p.productElement(i)).mkString(sep)
                    if sep.contains("\n") then s"$prefix(\n$initialPad$elems\n)"
                    else s"$prefix($elems)"
            case other => other.toString
    }
  end sumImpl

  private def productImpl[T: Type](sym: Quotes#reflectModule#Symbol)(using Quotes): Expr[Redactor[T]] =
    import quotes.reflect.*

    val typedSym          = sym.asInstanceOf[Symbol]
    val sensitiveAnnotSym = TypeRepr.of[sensitive].typeSymbol
    val className         = typedSym.name

    // Get constructor parameters
    val params = typedSym.primaryConstructor.paramSymss.headOption.getOrElse(Nil)

    if params.isEmpty then '{ instance[T](_ => ${ Expr(className) }) }
    else
      // Build field info at compile time
      val fieldInfo: List[(String, Boolean)] = params.map { param =>
        (param.name, param.hasAnnotation(sensitiveAnnotSym))
      }
      val fieldInfoExpr: Expr[List[(String, Boolean)]] = Expr(fieldInfo)
      val classNameExpr: Expr[String]                  = Expr(className)

      '{
        new Redactor[T]:
          private val fields    = $fieldInfoExpr
          private val className = $classNameExpr

          def redact(value: T): String = format(value, ", ", "")

          override def redactPretty(value: T, indent: Int): String =
            val pad = " " * indent
            format(value, s",\n$pad", pad)

          private def format(value: T, sep: String, initialPad: String): String =
            val p         = value.asInstanceOf[Product]
            val fieldStrs = fields.zipWithIndex.map { case ((name, isSensitive), idx) =>
              if isSensitive then s"$name={redacted}"
              else s"$name=${p.productElement(idx)}"
            }
            if sep.contains("\n") then s"$className(\n$initialPad${fieldStrs.mkString(sep)}\n)"
            else s"$className(${fieldStrs.mkString(sep)})"
      }
    end if
  end productImpl

end Redactor
