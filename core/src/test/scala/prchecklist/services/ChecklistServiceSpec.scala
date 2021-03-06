package prchecklist.services

import java.net.URI

import prchecklist.infrastructure._
import prchecklist.models._
import prchecklist.repositories._
import prchecklist.test._
import prchecklist.utils.RunnableFuture
import com.github.tarao.nonempty.NonEmpty
import org.scalatest._
import org.scalatest.time._
import org.scalatest.mock._
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class ChecklistServiceSpec extends FunSuite with Matchers with OptionValues with MockitoSugar with concurrent.ScalaFutures
    with WithTestDatabase
    with TestAppConfig
    with ChecklistServiceComponent
    with ReverseRouterComponent
    with PostgresDatabaseComponent
    with SlackNotificationServiceComponent
    with RepoRepositoryComponent
    with GitHubRepositoryComponent
    with ProjectConfigRepositoryComponent
    with ModelsComponent
    with GitHubHttpClientComponent
    with RedisComponent
    with GitHubConfig
    with ChecklistRepositoryComponent
    {

  override val reverseRouter: ReverseRouter = new ReverseRouter {
    override def authority = "localhost:3000"
    override def scheme = "http"
  }

  val githubAccessor = Visitor(login = "test", accessToken = "")

  def repoRepository = new RepoRepository

  def checklistRepository = new ChecklistRepository

  override def newGitHubRepository(githubAccessor: GitHubAccessible) = {
    val githubRepository = mock[GitHubRepository]

    when {
      githubRepository.getFileContent(any(), any(), any())
    } thenReturn {
      Future.successful(None)
    }

    when {
      githubRepository.getPullRequest(any(), any())
    } thenAnswer {
      new Answer[Future[GitHubTypes.PullRequest]] {
        override def answer(invocation: InvocationOnMock) = {
          Future.successful {
            GitHubTypes.PullRequest(
              number = invocation.getArgumentAt(1, classOf[Int]),
              title = "",
              body = "",
              state = "closed",
              head = GitHubTypes.CommitRef(GitHubTypes.Repo("", false), "xxx", "xxx"),
              base = GitHubTypes.CommitRef(GitHubTypes.Repo("", false), "xxx", "xxx"),
              commits = 1,
              assignee = None,
              user = GitHubTypes.User(login = "motemen", avatarUrl = "https://github.com/motemen.png")
            )
          }
        }
      }
    }

    githubRepository
  }

  def checklistService = new ChecklistService(githubAccessor)

  def http = new Http

  def redis = new Redis

  implicit override val patienceConfig = PatienceConfig(timeout = Span(3, Seconds), interval = Span(5, Millis))

  lazy val (repo, _) = repoRepository.create(GitHubTypes.Repo("motemen/test-repository", false), "<no token>").run

  test("getChecklist && checkChecklist succeeds") {
    val checkerUser = Visitor(login = "test", accessToken = "")

    val pr = GitHubTypes.PullRequestWithCommits(
      pullRequest = Factory.createGitHubPullRequest,
      commits = List(
        GitHubTypes.Commit("", GitHubTypes.CommitDetail(
          """Merge pull request #2 from motemen/feature-a
            |
            |feature-a
          """.stripMargin
        )),
        GitHubTypes.Commit("", GitHubTypes.CommitDetail(
          """Merge pull request #3 from motemen/feature-b
            |
            |feature-b
          """.stripMargin
        ))
      )
    )

    {
      val (checklist, created) = checklistService.getChecklist(repo, pr, stage = "").run
      checklist.checks.get(2).value shouldNot be('checked)
      checklist.checks.get(3).value shouldNot be('checked)
      checklist.checks.get(4) shouldBe 'empty

      checklistService.checkChecklist(checklist, checkerUser, featurePRNumber = 2).run
    }

    {
      val (checklist, created) = checklistService.getChecklist(repo, pr, stage = "").run
      checklist.checks.get(2).value shouldBe 'checked
      checklist.checks.get(3).value shouldNot be('checked)
      checklist.checks.get(4) shouldBe 'empty
      created shouldBe false

      checklistService.checkChecklist(checklist, checkerUser, featurePRNumber = 3).run

    }

    {
      val (checklist, created) = checklistService.getChecklist(repo, pr, stage = "").run
      checklist.checks.get(2).value shouldBe 'checked
      checklist.checks.get(3).value shouldBe 'checked
      checklist.checks.get(4) shouldBe 'empty
      created shouldBe false
    }
  }
}
