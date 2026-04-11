package mechanoid.macros

import zio.*
import zio.test.*

object ExpressionNameSpec extends ZIOSpecDefault:

  // Test objects and methods for expression name extraction
  object TestModule:
    def simpleMethod(x: Int): Int                 = x * 2
    def multiArgMethod(a: Int, b: String): String = s"$a-$b"
    def curried(a: Int)(b: Int): Int              = a + b
    def generic[T](value: T): T                   = value
    val property: Int                             = 42
    object Nested:
      def nestedMethod(x: Int): Int = x
      val nestedProperty: String    = "nested"

  class TestClass:
    def instanceMethod(x: Int): Int = x
    val instanceProperty: Int       = 100

  def spec = suite("ExpressionNameSpec")(
    suite("method calls")(
      test("extracts name from simple method call") {
        val name = ExpressionName.of(TestModule.simpleMethod(1))
        assertTrue(name.contains("simpleMethod"))
      },
      test("extracts name from multi-argument method call") {
        val name = ExpressionName.of(TestModule.multiArgMethod(1, "test"))
        assertTrue(name.contains("multiArgMethod"))
      },
      test("extracts name from curried method call") {
        val name = ExpressionName.of(TestModule.curried(1)(2))
        assertTrue(name.contains("curried"))
      },
      test("extracts name from generic method call") {
        val name = ExpressionName.of(TestModule.generic[Int](42))
        assertTrue(name.contains("generic"))
      },
      test("extracts name from nested object method") {
        val name = ExpressionName.of(TestModule.Nested.nestedMethod(1))
        assertTrue(
          name.contains("nestedMethod"),
          name.contains("Nested"),
        )
      },
    ),
    suite("property access")(
      test("extracts name from object property") {
        val name = ExpressionName.of(TestModule.property)
        assertTrue(name.contains("property"))
      },
      test("extracts name from nested object property") {
        val name = ExpressionName.of(TestModule.Nested.nestedProperty)
        assertTrue(
          name.contains("nestedProperty"),
          name.contains("Nested"),
        )
      },
      test("extracts name from instance property") {
        val instance = new TestClass()
        val name     = ExpressionName.of(instance.instanceProperty)
        assertTrue(name.contains("instanceProperty"))
      },
    ),
    suite("identifiers")(
      test("extracts name from local val") {
        val localVal = 42
        val name     = ExpressionName.of(localVal)
        assertTrue(name.contains("localVal"))
      },
      test("extracts name from local var") {
        var localVar = 42
        localVar += 0 // Use the var to avoid warning
        val name = ExpressionName.of(localVar)
        assertTrue(name.contains("localVar"))
      },
    ),
    suite("instance methods")(
      test("extracts name from instance method call") {
        val instance = new TestClass()
        val name     = ExpressionName.of(instance.instanceMethod(5))
        assertTrue(name.contains("instanceMethod"))
      }
    ),
    suite("complex expressions")(
      test("extracts name from block expression returning method call") {
        val name = ExpressionName.of {
          val _ = 1
          TestModule.simpleMethod(2)
        }
        assertTrue(name.contains("simpleMethod"))
      },
      test("handles typed expression") {
        val name = ExpressionName.of((TestModule.property: Int))
        assertTrue(name.contains("property"))
      },
    ),
    suite("fully qualified names")(
      test("includes package and object in full name") {
        val name = ExpressionName.of(TestModule.simpleMethod(1))
        assertTrue(
          name.contains("mechanoid.macros"),
          name.contains("ExpressionNameSpec"),
          name.contains("TestModule"),
          name.contains("simpleMethod"),
        )
      }
    ),
    suite("edge cases")(
      test("handles literal expressions") {
        // Literals don't have symbols, should use fallback
        val name = ExpressionName.of(42)
        assertTrue(name.nonEmpty)
      },
      test("handles string literals") {
        val name = ExpressionName.of("hello")
        assertTrue(name.nonEmpty)
      },
      test("handles arithmetic expressions") {
        val name = ExpressionName.of(1 + 2)
        assertTrue(name.nonEmpty)
      },
      test("handles list construction") {
        val name = ExpressionName.of(List(1, 2, 3))
        assertTrue(name.contains("List") || name.contains("apply"))
      },
    ),
  )
end ExpressionNameSpec
