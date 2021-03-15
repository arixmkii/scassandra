package com.evolutiongaming.scassandra

import cats.effect.{Concurrent, Sync}
import cats.effect.implicits._
import cats.syntax.all._
import com.datastax.driver.core.{Row, ResultSet => ResultSetJ}
import com.evolutiongaming.scassandra.util.FromGFuture
import com.evolutiongaming.sstream.Stream
import com.evolutiongaming.sstream.FoldWhile._


object ResultSet {

  implicit class ResultSetOps(val self: ResultSetJ) extends AnyVal {

    def stream[F[_] : Concurrent : FromGFuture]: Stream[F, Row] =
      ResultSet(self)

  }

  implicit class FResultSetOps[F[_]](val self: F[ResultSetJ]) extends AnyVal {

    def stream(implicit concurrent: Concurrent[F], fromGFuture: FromGFuture[F]): Stream[F, Row] = for {
      resultSet <- Stream.lift(self)
      stream    <- ResultSet[F](resultSet)
    } yield stream

  }

  def apply[F[_] : Concurrent : FromGFuture](resultSet: ResultSetJ): Stream[F, Row] = {

    val iterator = resultSet.iterator()

    val fetch = FromGFuture[F].apply { resultSet.fetchMoreResults() }.void

    val fetched = Sync[F].delay { resultSet.isFullyFetched }

    val next = Sync[F].delay { List.fill(resultSet.getAvailableWithoutFetching)(iterator.next()) }

    apply[F, Row](fetch, fetched, next)
  }

  def apply[F[_] : Concurrent, A](
    fetch: F[Unit],
    fetched: F[Boolean],
    next: F[List[A]]
  ): Stream[F, A] = new Stream[F, A] {

    def foldWhileM[L, R](l: L)(f: (L, A) => F[Either[L, R]]) = {

      l.tailRecM[F, Either[L, R]] { l =>

        def apply(rows: List[A]) = {
          for {
            result <- rows.foldWhileM(l)(f)
          } yield {
            result.asRight[L]
          }
        }

        def fetchAndApply(rows: List[A]) = {
          for {
            fetching <- fetch.start
            result <- rows.foldWhileM(l)(f)
            result <- result match {
              case l: Left[L, R]  => fetching.join as l.rightCast[Either[L, R]]
              case r: Right[L, R] => r.leftCast[L].asRight[L].pure[F]
            }
          } yield result
        }

        for {
          fetched <- fetched
          rows <- next
          result <- if (fetched) apply(rows) else fetchAndApply(rows)
        } yield result
      }
    }
  }
}
