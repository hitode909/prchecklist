package prchecklist.services

import prchecklist.infrastructure.PostgresDatabaseComponent
import prchecklist.repositories.RepoRepositoryComponent
import prchecklist.utils.RunnableFuture
import prchecklist.test._
import prchecklist.models._

import org.scalatest._

class RepoServiceSpec extends FunSuite with Matchers
    with WithTestDatabase
    with RepoRepositoryComponent
    with PostgresDatabaseComponent
    with TestAppConfig
    with ModelsComponent
    with GitHubConfig {

  override val repoRepository = new RepoRepository

  test("create && get") {

    repoRepository.get("owner", "name").run shouldBe 'empty

    repoRepository.create(GitHubTypes.Repo("owner/name", false), "accessToken").run match {
      case (repo, created) =>
        repo.owner shouldBe "owner"
        repo.name shouldBe "name"
        repo.defaultAccessToken shouldBe "accessToken"
    }

    repoRepository.get("owner", "name").run shouldBe 'defined
  }
}
