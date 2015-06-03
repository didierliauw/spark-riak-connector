package com.basho.spark.connector.rdd

import java.io.IOException

import com.basho.riak.client.api.RiakClient
import com.basho.riak.client.core.{RiakNode, RiakCluster}
import com.google.common.net.HostAndPort

import scala.collection.JavaConversions

import org.apache.spark.{Logging, SparkConf}

import scala.collection.concurrent.TrieMap
;

/** Provides and manages connections to Riak.
  * can be either given explicitly or automatically configured from `SparkConf`.
  * The connection options are:
  *   - `spark.riak.connection.hosts`:               contact point to connect to the Cassandra cluster, defaults to spark master host
  */
class RiakConnector(conf: RiakConnectorConf)
  extends Serializable with Logging {

  // scalastyle:off import.grouping
  import com.basho.spark.connector.rdd.RiakConnector.createSession
  // scalastyle:on import.grouping

  private[this] var _config = conf

  /** Known cluster hosts. This is going to return all cluster hosts after at least one successful connection has been made */
  def hosts: Set[HostAndPort] = _config.hosts

  def openSession(): RiakClient = {
    createSession(_config)
  }

  def withSessionDo[T](code: RiakClient => T): T = {
    closeSessionAfterUse(openSession()) { session =>
      code(session)
    }
  }

  def closeSessionAfterUse[T](closeable: RiakClient)(code: RiakClient => T): T =
    try code(closeable) finally {
      closeable.shutdown()
    }
}

object RiakConnector extends Logging {
  private val DEFAULT_MIN_NUMBER_OF_CONNECTIONS = 10
  private val DEFAULT_MAX_NUMBER_OF_CONNECTIONS = 10
  private val sessionCache = new TrieMap[RiakConnectorConf, RiakClient]()

  private def createSession(conf: RiakConnectorConf): RiakClient = {
    lazy val addresses = conf.hosts.map(_.toString)
    lazy val endpointsStr = addresses.mkString("", ", ", "")
    logDebug(s"Attempting to create java connection to Riak at $endpointsStr")

    try {
      logDebug(s"Attempting to create riak client at $addresses")
      val builder = new RiakNode.Builder()
        .withMinConnections(DEFAULT_MIN_NUMBER_OF_CONNECTIONS)
        .withMaxConnections(DEFAULT_MAX_NUMBER_OF_CONNECTIONS)



      val nodes = conf.hosts.map { (h: HostAndPort) =>
        builder.withRemoteAddress(h.getHostText)
        builder.withRemotePort(h.getPort)
        builder.build()
      }

      val ns = JavaConversions.bufferAsJavaList(nodes.toBuffer)
      val cluster = RiakCluster.builder(ns).build()
      cluster.start()

      new RiakClient(cluster)
    }
    catch {
      case e: Throwable =>
        throw new IOException(
          s"Failed to create RiakClient for $endpointsStr", e)
    }
  }

  private def destroySession(session: RiakClient): Unit = {
    session.shutdown()
    logInfo(s"RiakClient has been destroyed")
  }

  Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
    def run(): Unit = {
      sessionCache.foreach( _._2.shutdown() )
    }
  }))

  /** Returns a RiakConnector created from properties found in the `SparkConf` object */
  def apply(conf: SparkConf): RiakConnector = {
    new RiakConnector(RiakConnectorConf(conf))
  }

  /** Returns a RiakConnector created from explicitly given connection configuration. */
  def apply(hosts: Set[HostAndPort]): RiakConnector = {

    val config = RiakConnectorConf(hosts)
    new RiakConnector(config)
  }
}
