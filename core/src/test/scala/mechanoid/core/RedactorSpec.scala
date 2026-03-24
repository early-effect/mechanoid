package mechanoid.core

import zio.*
import zio.test.*
import Redactor.{redacted, redactedPretty}

object RedactorSpec extends ZIOSpecDefault:

  // Test types - defined outside the spec to ensure macro can see them
  case class SimpleCase(id: Int, @sensitive secret: String, name: String)

  case class AllSensitive(@sensitive a: String, @sensitive b: String)

  case class NoSensitive(x: Int, y: String)

  enum TestCommand:
    case Create(id: Int, @sensitive password: String)
    case Update(@sensitive oldValue: String, @sensitive newValue: String)
    case Delete(id: Int)

  // Custom redactor test
  case class CustomType(value: String)
  given Redactor[CustomType] = Redactor.instance(t => s"CustomType(***${t.value.length}***)")

  def spec = suite("RedactorSpec")(
    suite("automatic redaction")(
      test("case class with @sensitive fields") {
        val obj    = SimpleCase(42, "my-secret", "visible")
        val result = obj.redacted
        assertTrue(
          result.contains("id=42"),
          result.contains("secret={redacted}"),
          result.contains("name=visible"),
          !result.contains("my-secret"),
        )
      },
      test("case class with all @sensitive fields") {
        val obj    = AllSensitive("secret1", "secret2")
        val result = obj.redacted
        assertTrue(
          result.contains("a={redacted}"),
          result.contains("b={redacted}"),
          !result.contains("secret1"),
          !result.contains("secret2"),
        )
      },
      test("case class with no @sensitive fields shows all values") {
        val obj    = NoSensitive(123, "hello")
        val result = obj.redacted
        assertTrue(
          result.contains("x=123"),
          result.contains("y=hello"),
        )
      },
      test("enum with @sensitive fields") {
        val create: TestCommand = TestCommand.Create(1, "secret-pw")
        val update: TestCommand = TestCommand.Update("oldSecret", "newSecret")
        val delete: TestCommand = TestCommand.Delete(99)

        assertTrue(
          create.redacted.contains("id=1"),
          create.redacted.contains("password={redacted}"),
          !create.redacted.contains("secret-pw"),
          update.redacted.contains("oldValue={redacted}"),
          update.redacted.contains("newValue={redacted}"),
          !update.redacted.contains("oldSecret"),
          !update.redacted.contains("newSecret"),
          delete.redacted.contains("id=99"),
        )
      },
    ),
    suite("custom redactors")(
      test("custom redactor takes precedence") {
        val obj    = CustomType("hello")
        val result = obj.redacted
        assertTrue(
          result == "CustomType(***5***)"
        )
      }
    ),
    suite("built-in types")(
      test("String is fully redacted") {
        val s      = "sensitive-string"
        val result = s.redacted
        assertTrue(result == "{redacted}")
      },
      test("Int uses toString") {
        val n      = 42
        val result = n.redacted
        assertTrue(result == "42")
      },
    ),
    suite("redactAll helper")(
      test("redactAll shows only class name") {
        given Redactor[SimpleCase] = Redactor.redactAll
        val obj                    = SimpleCase(42, "secret", "name")
        val result                 = Redactor[SimpleCase].redact(obj)
        assertTrue(
          result == "SimpleCase({redacted})",
          !result.contains("secret"),
          !result.contains("name"),
        )
      }
    ),
    suite("pretty printing")(
      test("redactedPretty formats with newlines") {
        val obj    = SimpleCase(42, "my-secret", "visible")
        val result = obj.redactedPretty
        assertTrue(
          result.contains("\n"),
          result.contains("id=42"),
          result.contains("secret={redacted}"),
          result.contains("name=visible"),
          !result.contains("my-secret"),
        )
      },
      test("redactedPretty with custom indent") {
        val obj    = SimpleCase(42, "secret", "name")
        val result = obj.redactedPretty(4)
        assertTrue(
          result.contains("    "), // 4 spaces
          result.contains("\n"),
        )
      },
      test("enum redactedPretty formats with newlines") {
        val cmd: TestCommand = TestCommand.Create(1, "secret-pw")
        val result           = cmd.redactedPretty
        assertTrue(
          result.contains("\n"),
          result.contains("id=1"),
          result.contains("password={redacted}"),
          !result.contains("secret-pw"),
        )
      },
    ),
  ) @@ TestAspect.sequential

end RedactorSpec
