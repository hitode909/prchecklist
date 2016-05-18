package prchecklist.services

import prchecklist.infrastructure
import prchecklist.services
import prchecklist.repositories
import prchecklist.models
import prchecklist.models.GitHubTypes
import org.slf4j.LoggerFactory
import com.github.tarao.nonempty.NonEmpty

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Success

/**
 * ChecklistServiceComponent is the main logic of prchecklist.
 */
trait ChecklistServiceComponent {
  self: infrastructure.DatabaseComponent
    with models.ModelsComponent
    with services.SlackNotificationServiceComponent
    with repositories.RepoRepositoryComponent
    with repositories.GitHubRepositoryComponent
    with repositories.ProjectConfigRepositoryComponent
    with repositories.ChecklistRepositoryComponent
      =>

  class ChecklistService(githubAccessor: GitHubAccessible) {
    def logger = LoggerFactory.getLogger(getClass)

    val githubRepository = self.githubRepository(githubAccessor)
    val projectConfigRepository = self.projectConfigRepository(githubRepository)

    private def mergedPullRequests(commits: List[GitHubTypes.Commit]): Option[NonEmpty[PullRequestReference]] = {
      NonEmpty.fromTraversable {
        commits.flatMap {
          c =>
            """^Merge pull request #(\d+) from [^\n]+\s+(.+)""".r.findFirstMatchIn(c.commit.message) map {
              m => PullRequestReference(m.group(1).toInt, m.group(2))
            }
        }
      }
    }

    def getChecklist(repo: Repo, prWithCommits: GitHubTypes.PullRequestWithCommits, stage: String): Future[(ReleaseChecklist, Boolean)] = {
      val db = getDatabase

      // repo = repoRepository.get(repoOwner, repoName)
      // prWithCommits = githubRepository.getPullRequestWithCommits(repo, prNumber)

      mergedPullRequests(prWithCommits.commits) match {
        case None =>
          Future.failed(new IllegalStateException("No merged pull requests"))

        case Some(prRefs) =>
          checklistRepository.getChecks(repo, prWithCommits.pullRequest.number, stage, prRefs).map {
            case (checklistId, checks, created) =>
              (ReleaseChecklist(checklistId, repo, prWithCommits.pullRequest, stage, prRefs.toList, checks), created)
          }
      }
    }

    /**
     * checkChecklist is the most important logic
     */
    def checkChecklist(checklist: ReleaseChecklist, checkerUser: Visitor, featurePRNumber: Int): Future[ReleaseChecklist] = {
      // TODO: handle errors
      val fut = checklistRepository.createCheck(checklist, checkerUser, featurePRNumber)
      fut.onSuccess {
        case newCkecklist =>
          projectConfigRepository.loadProjectConfig(checklist.repo, s"pull/${checklist.pullRequest.number}/head") andThen {
            case Success(Some(config)) =>
              Future.traverse(config.notification.channels) {
                case (name, ch) =>
                  val title = checklist.featurePullRequest(featurePRNumber).map(_.title) getOrElse "(unknown)"
                  val additionalMssage = if (newCkecklist.allGreen) { "\n:tada::tada:All ckecks are done:tada::tada:" } else { "" }
                  slackNotificationService.send(ch.url, s"""[<${checklist.pullRequestUrl}|${checklist.repo.fullName} #${checklist.pullRequest.number}>] <${checklist.featurePullRequestUrl(featurePRNumber)}|#$featurePRNumber "$title"> checked by ${checkerUser.login} ${additionalMssage}""")
              }
          } onFailure {
            case e =>
              logger.warn(s"Error while sending notification: $e")
          }
      }
      fut
    }

    def uncheckChecklist(checklist: ReleaseChecklist, checkerUser: Visitor, featurePRNumber: Int): Future[Unit] = {
      checklistRepository.deleteCheck(checklist, checkerUser, featurePRNumber)
    }
  }
}
