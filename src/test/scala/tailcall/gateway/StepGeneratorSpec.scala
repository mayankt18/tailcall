package tailcall.gateway

import tailcall.gateway.ast.Document
import tailcall.gateway.dsl.scala.Orc
import tailcall.gateway.dsl.scala.Orc.Field
import tailcall.gateway.remote._
import tailcall.gateway.service._
import zio.ZIO
import zio.test.Assertion.equalTo
import zio.test.{ZIOSpecDefault, assertZIO}

object StepGeneratorSpec extends ZIOSpecDefault {

  def spec = {
    suite("StepGenerator")(
      test("static value") {
        val orc     = Orc("Query" -> List("id" -> Field.output.to("String").resolveWith(100)))
        val program = execute(orc)("query {id}")
        assertZIO(program)(equalTo("""{"id":100}"""))
      },
      test("with args") {
        val orc     = Orc(
          "Query" -> List(
            "sum" -> Field.output.to("Int").withArgument("a" -> Field.input.to("Int"), "b" -> Field.input.to("Int"))
              .resolveWithFunction { ctx =>
                {
                  (for {
                    a <- ctx.toTypedPath[Int]("args", "a")
                    b <- ctx.toTypedPath[Int]("args", "b")
                  } yield a + b).toDynamic
                }
              }
          )
        )
        val program = execute(orc)("query {sum(a: 1, b: 2)}")
        assertZIO(program)(equalTo("""{"sum":3}"""))
      },
      test("with nesting") {
        // type Query {foo: Foo}
        // type Foo {bar: Bar}
        // type Bar {value: Int}

        val orc = Orc(
          "Query" -> List("foo" -> Field.output.to("Foo")),
          "Foo"   -> List("bar" -> Field.output.to("Bar")),
          "Bar"   -> List("value" -> Field.output.to("Int").resolveWith(100))
        )

        val program = execute(orc)("query {foo { bar { value }}}")
        assertZIO(program)(equalTo("{\"foo\":{\"bar\":{\"value\":100}}}"))
      },
      test("with nesting array") {
        // type Query {foo: Foo}
        // type Foo {bar: [Bar]}
        // type Bar {value: Int}

        val orc = Orc(
          "Query" -> List("foo" -> Field.output.to("Foo")),
          "Foo"   -> List("bar" -> Field.output.toList("Bar").resolveWith(List(100, 200, 300))),
          "Bar"   -> List("value" -> Field.output.to("Int").resolveWith(100))
        )

        val program = execute(orc)("query {foo { bar { value }}}")
        assertZIO(program)(equalTo("""{"foo":{"bar":[{"value":100},{"value":100},{"value":100}]}}"""))
      },
      test("with nesting array ctx") {
        // type Query {foo: Foo}
        // type Foo {bar: [Bar]}
        // type Bar {value: Int}
        val orc = Orc(
          "Query" -> List("foo" -> Field.output.to("Foo")),
          "Foo"   -> List("bar" -> Field.output.toList("Bar").resolveWith(List(100, 200, 300))),
          "Bar"   -> List("value" -> Field.output.to("Int").resolveWithFunction {
            _.toTypedPath[Int]("value").map(_ + Remote(1)).toDynamic
          })
        )

        val program = execute(orc)("query {foo { bar { value }}}")
        assertZIO(program)(equalTo("""{"foo":{"bar":[{"value":101},{"value":201},{"value":301}]}}"""))
      },
      test("with nesting level 3") {
        // type Query {foo: Foo}
        // type Foo {bar: [Bar]}
        // type Bar {baz: [Baz]}
        // type Baz{value: Int}
        val orc = Orc(
          "Query" -> List("foo" -> Field.output.to("Foo")),
          "Foo"   -> List("bar" -> Field.output.toList("Bar").resolveWith(List(100, 200, 300))),
          "Bar"   -> List("baz" -> Field.output.toList("Baz").resolveWithFunction {
            _.toTypedPath[Int]("value").map(_ + Remote(1)).toDynamic
          }),
          "Baz"   -> List("value" -> Field.output.to("Int").resolveWithFunction {
            _.toTypedPath[Option[Int]]("value").flatten.map(_ + Remote(1)).toDynamic
          })
        )

        val program = execute(orc)("query {foo { bar { baz {value} }}}")
        assertZIO(program)(equalTo(
          """{"foo":{"bar":[{"baz":[{"value":102}]},{"baz":[{"value":202}]},{"baz":[{"value":302}]}]}}"""
        ))
      },
      test("parent") {
        // type Query {foo: Foo}
        // type Foo {bar: Bar}
        // type Bar{baz: Baz}
        // type Baz{value: Int}
        val orc     = Orc(
          "Query" -> List("foo" -> Field.output.to("Foo")),
          "Foo"   -> List("bar" -> Field.output.to("Bar").resolveWith(100)),
          "Bar"   -> List("baz" -> Field.output.to("Baz").resolveWith(200)),
          "Baz"   -> List("value" -> Field.output.to("Int").resolveWithFunction {
            _.path("parent", "value").debug("here").map(_.toTyped[Int]).toDynamic
          })
        )
        val program = execute(orc)("query {foo { bar { baz {value} }}}")
        assertZIO(program)(equalTo("""{"foo":{"bar":{"baz":{"value":100}}}}"""))

      },
      test("partial resolver") {
        // type Query {foo: Foo}
        // type Foo {a: Int, b: Int, c: Int}
        val orc     = Orc(
          "Query" -> List("foo" -> Field.output.to("Foo").resolveWith(Map("a" -> 1, "b" -> 2))),
          "Foo"   -> List(
            "a" -> Field.output.to("Int").resolveWithFunction(_.path("value", "a").toDynamic),
            "b" -> Field.output.to("Int").resolveWithFunction(_.path("value", "b").toDynamic),
            "b" -> Field.output.to("Int").resolveWithParent,
            "c" -> Field.output.to("Int").resolveWith(3)
          )
        )
        val program = execute(orc)("query {foo { a b c }}")
        assertZIO(program)(equalTo("""{"foo":{"a":1,"b":2,"c":3}}"""))

      }
    ).provide(GraphQLGenerator.live, TypeGenerator.live, StepGenerator.live, EvaluationRuntime.live)
  }

  def execute(orc: Orc)(query: String): ZIO[GraphQLGenerator, Throwable, String] =
    orc.toDocument.flatMap(execute(_)(query))

  def execute(doc: Document)(query: String): ZIO[GraphQLGenerator, Throwable, String] =
    for {
      graphQL     <- doc.toGraphQL
      interpreter <- graphQL.interpreter
      result <- interpreter.execute(query, skipValidation = true) // TODO: enable validation after __type is available
      _      <- result.errors.headOption match {
        case Some(error) => ZIO.fail(error)
        case None        => ZIO.unit
      }
    } yield result.data.toString
}