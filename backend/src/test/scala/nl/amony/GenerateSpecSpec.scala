package nl.amony

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sttp.apispec.openapi.{Operation, ResponsesCodeKey}

class GenerateSpecSpec extends AnyWordSpec with Matchers:

  private def operations: List[Operation] =
    GenerateSpec.documentation.paths.pathItems.values
      .flatMap(item => List(item.get, item.put, item.post, item.delete, item.options, item.head, item.patch, item.trace))
      .flatten
      .toList

  "GenerateSpec.documentation" should {

    "document an implicit 500 on every operation" in {
      operations should not be empty

      operations.foreach { operation =>
        withClue(s"operation '${operation.operationId.getOrElse("<unnamed>")}' ") {
          operation.responses.responses.keySet should contain(ResponsesCodeKey(500))
        }
      }
    }

    "reference the shared ErrorBody schema" in {
      GenerateSpec.documentation.components.flatMap(_.schemas.get("ErrorBody")) shouldBe defined
    }
  }
