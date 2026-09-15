package mechanoid.compiletime

import zio.test.*
import mechanoid.core.Finite

/** Compile-time tests for assembly validation.
  *
  * This module uses `-Werror` to turn warnings into errors, allowing us to test that certain code patterns emit
  * compile-time warnings (which become errors here).
  */
object CompileTimeRejectSpec extends ZIOSpecDefault:

  enum S derives Finite:
    case A, B, C

  enum E derives Finite:
    case E1, E2, E3

  def spec = suite("Compile-time duplicate rejection")(
    test("direct duplicate in assembly() is rejected") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        assembly[S, E](S.A via E.E1 to S.B, S.A via E.E1 to S.C)
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("duplicate in assemblyAll is rejected") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        assemblyAll[S, E]:
          S.A via E.E1 to S.B
          S.A via E.E1 to S.C
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("duplicate through ++ composition is rejected") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        assembly[S, E](S.A via E.E1 to S.B) ++
          assembly[S, E](S.A via E.E1 to S.C)
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("duplicate through combine() is rejected") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        combine(
          assembly[S, E](S.A via E.E1 to S.B),
          assembly[S, E](S.A via E.E1 to S.C),
        )
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("duplicate through Machine(assembly(...)) is rejected") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        Machine(assembly[S, E](S.A via E.E1 to S.B, S.A via E.E1 to S.C))
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("override resolves duplicate in assembly()") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        assembly[S, E](
          S.A via E.E1 to S.B,
          (S.A via E.E1 to S.C) @@ Aspect.overriding,
        )
      """)
      assertZIO(result)(Assertion.isRight)
    },
    test("override resolves duplicate in combine()") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        combine(
          assembly[S, E](S.A via E.E1 to S.B),
          assembly[S, E]((S.A via E.E1 to S.C) @@ Aspect.overriding),
        )
      """)
      assertZIO(result)(Assertion.isRight)
    },
    test("override resolves duplicate in ++") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        assembly[S, E](S.A via E.E1 to S.B) ++
          assembly[S, E]((S.A via E.E1 to S.C) @@ Aspect.overriding)
      """)
      assertZIO(result)(Assertion.isRight)
    },
    test("no duplicates compiles successfully") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B, C }
        enum E derives Finite { case E1, E2 }
        assembly[S, E](S.A via E.E1 to S.B, S.B via E.E2 to S.C)
      """)
      assertZIO(result)(Assertion.isRight)
    },
    test("computed to parent type is rejected") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite { case A, B }
        enum E derives Finite { case E1 }
        assembly[S, E]((S.A via E.E1).to[S]((s, _) => s))
      """)
      assertZIO(result)(Assertion.isLeft)
    },
    test("computed to leaf type is accepted") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite:
          case A
          case B(n: Int)
        enum E derives Finite { case E1 }
        assembly[S, E]((S.A via E.E1).to[S.B]((_, _) => S.B(0)))
      """)
      assertZIO(result)(Assertion.isRight)
    },
    test("duplicate computed edges are rejected") {
      val result = typeCheck("""
        import mechanoid.machine.*
        import mechanoid.core.Finite
        enum S derives Finite:
          case A
          case B(n: Int)
          case C(n: Int)
        enum E derives Finite { case E1 }
        assembly[S, E](
          (S.A via E.E1).to[S.B]((_, _) => S.B(0)),
          (S.A via E.E1).to[S.C]((_, _) => S.C(0)),
        )
      """)
      assertZIO(result)(Assertion.isLeft)
    },
  )

end CompileTimeRejectSpec
