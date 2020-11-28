package viper.server.vsi

import akka.actor.ActorRef
import akka.stream.scaladsl.SourceQueueWithComplete
import org.reactivestreams.Publisher

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}

sealed trait JobId {
  val id: Int
  def tag: String
  override def toString: String = s"${tag}_id_${id}"
}

case class AstJobId(id: Int) extends JobId {
  def tag = "ast"
}

case class VerJobId(id: Int) extends JobId {
  def tag = "ver"
}

sealed trait JobHandle {
  def tag: String  // identify the kind of job this is
  val job_actor: ActorRef
  val queue: SourceQueueWithComplete[Envelope]
  val publisher: Publisher[Envelope]
}

case class AstHandle[R](job_actor: ActorRef,
                        queue: SourceQueueWithComplete[Envelope],
                        publisher: Publisher[Envelope],
                        artifact: Future[R]) extends JobHandle {
  def tag = "AST"
}

case class VerHandle(job_actor: ActorRef,
                     queue: SourceQueueWithComplete[Envelope],
                     publisher: Publisher[Envelope]) extends JobHandle {
  def tag = "VER"
}

class JobPool[S <: JobId, T <: JobHandle](val tag: String, val MAX_ACTIVE_JOBS: Int = 3)
                                         (implicit val jid_fact: Int => S,
                                          ctx: ExecutionContext) {

  private val _jobHandles: mutable.Map[S, Promise[T]] = mutable.Map()
  private val _jobExecutors: mutable.Map[S, () => Future[T]] = mutable.Map()
  private val _jobCache: mutable.Map[S, Future[T]] = mutable.Map()
  def jobHandles: Map[S, Future[T]] = _jobHandles.map{ case (id, hand) => (id, hand.future) }.toMap

  private var _nextJobId: Int = 0

  def newJobsAllowed: Boolean = jobHandles.size < MAX_ACTIVE_JOBS

  def bookNewJob(job_executor: S => Future[T]): S = {
    require(newJobsAllowed)

    val new_jid: S = jid_fact(_nextJobId)

    _jobHandles(new_jid) = Promise()
    _jobExecutors(new_jid) = () => {
      if (_jobCache.contains(new_jid)) {
        /** This prevents recomputing the same future multiple times. */
        _jobCache(new_jid)
      } else {
        val t_fut = job_executor(new_jid)
        _jobCache(new_jid) = t_fut
        t_fut
      }
    }

    _nextJobId = _nextJobId + 1
    new_jid
  }

  def discardJob(jid: S): Unit = {
    _jobHandles -= jid
    _jobExecutors -= jid
    _jobCache -= jid
  }

  def lookupJob(jid: S): Option[Future[T]] = {
    _jobHandles.get(jid).map((promise: Promise[T]) => {
      promise.completeWith(_jobExecutors(jid)())

      //promise.future.map(_.queue.watchCompletion().onComplete(_ => discardJob(jid)))

      promise.future
    })
  }
}
