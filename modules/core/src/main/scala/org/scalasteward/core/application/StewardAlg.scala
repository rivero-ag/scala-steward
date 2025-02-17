/*
 * Copyright 2018-2021 Scala Steward contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.scalasteward.core.application

import better.files.File
import cats.effect.{BracketThrow, ExitCode}
import cats.syntax.all._
import fs2.Stream
import org.scalasteward.core.buildtool.sbt.SbtAlg
import org.scalasteward.core.git.GitAlg
import org.scalasteward.core.io.{FileAlg, WorkspaceAlg}
import org.scalasteward.core.nurture.NurtureAlg
import org.scalasteward.core.repocache.RepoCacheAlg
import org.scalasteward.core.update.PruningAlg
import org.scalasteward.core.util
import org.scalasteward.core.util.DateTimeAlg
import org.scalasteward.core.util.logger.LoggerOps
import org.scalasteward.core.vcs.data.Repo
import org.scalasteward.core.vcs.github.{GitHubApp, GitHubAppApiAlg, GitHubAuthAlg}
import org.typelevel.log4cats.Logger

import scala.concurrent.duration._

final class StewardAlg[F[_]](config: Config)(implicit
    dateTimeAlg: DateTimeAlg[F],
    fileAlg: FileAlg[F],
    gitAlg: GitAlg[F],
    githubAppApiAlg: GitHubAppApiAlg[F],
    githubAuthAlg: GitHubAuthAlg[F],
    logger: Logger[F],
    nurtureAlg: NurtureAlg[F],
    pruningAlg: PruningAlg[F],
    repoCacheAlg: RepoCacheAlg[F],
    sbtAlg: SbtAlg[F],
    selfCheckAlg: SelfCheckAlg[F],
    streamCompiler: Stream.Compiler[F, F],
    workspaceAlg: WorkspaceAlg[F],
    F: BracketThrow[F]
) {
  private def readRepos(reposFile: File): Stream[F, Repo] =
    Stream.evals[F, List, Repo] {
      fileAlg.readFile(reposFile).map { maybeContent =>
        val regex = """-\s+(.+)/([^/]+)""".r
        val content = maybeContent.getOrElse("")
        content.linesIterator.collect { case regex(owner, repo) =>
          Repo(owner.trim, repo.trim)
        }.toList
      }
    }

  private def getGitHubAppRepos(githubApp: GitHubApp): Stream[F, Repo] =
    Stream.evals[F, List, Repo] {
      for {
        jwt <- githubAuthAlg.createJWT(githubApp, 2.minutes)
        installations <- githubAppApiAlg.installations(jwt)
        repositories <- installations.traverse { installation =>
          githubAppApiAlg
            .accessToken(jwt, installation.id)
            .flatMap(token => githubAppApiAlg.repositories(token.token))
        }
        repos <- repositories.flatMap(_.repositories).flatTraverse { repo =>
          repo.full_name.split('/') match {
            case Array(owner, name) => F.pure(List(Repo(owner, name)))
            case _                  => logger.error(s"invalid repo $repo").as(List.empty[Repo])
          }
        }
      } yield repos
    }

  private def steward(repo: Repo): F[Either[Throwable, Unit]] = {
    val label = s"Steward ${repo.show}"
    logger.infoTotalTime(label) {
      logger.attemptLogLabel(util.string.lineLeftRight(label), Some(label)) {
        F.guarantee {
          for {
            (data, fork) <- repoCacheAlg.checkCache(repo)
            (attentionNeeded, updates) <- pruningAlg.needsAttention(data)
            _ <- if (attentionNeeded) nurtureAlg.nurture(data, fork, updates) else F.unit
          } yield ()
        }(gitAlg.removeClone(repo))
      }
    }
  }

  def runF: F[ExitCode] =
    logger.infoTotalTime("run") {
      for {
        _ <- selfCheckAlg.checkAll
        _ <- workspaceAlg.cleanWorkspace
        exitCode <- sbtAlg.addGlobalPlugins {
          (config.githubApp.map(getGitHubAppRepos).getOrElse(Stream.empty) ++
            readRepos(config.reposFile))
            .evalMap(steward)
            .compile
            .foldMonoid
            .map(_.fold(_ => ExitCode.Error, _ => ExitCode.Success))
        }
      } yield exitCode
    }
}
