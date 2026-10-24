package nl.amony.platform.database

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class DatabaseChangelogSpec extends AnyWordSpecLike with Matchers:

  "Database.Changelog" should {

    "keep the historical classpath path that Liquibase persisted" in {
      Database.Changelog shouldBe "db/00-changelog.yaml"
      Option(getClass.getClassLoader.getResource(Database.Changelog)) should not be empty
    }
  }
