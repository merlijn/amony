package nl.amony.modules.search.solr

import java.nio.file.{Files, Path}
import java.util.Properties

import cats.effect.{IO, Resource}
import org.apache.solr.client.solrj.SolrClient
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer
import org.apache.solr.core.CoreContainer
import scribe.Logging

import nl.amony.modules.search.solr.SolrResource.logger
import nl.amony.modules.search.solr.SolrSearchService.solrTarGzResource

object SolrResource extends Logging {

  val collections = Seq("resources")

  def make(config: SolrConfig): Resource[IO, SolrClient] = Resource
    .make[IO, SolrClient](IO.blocking {

      val solrHome: Path = Path.of(config.path).toAbsolutePath.normalize()
      logger.info(s"Solr home: $solrHome")

      if Files.exists(solrHome) && !Files.isDirectory(solrHome) then
        throw new RuntimeException(s"Solr home is not a directory: $solrHome")

      if !Files.exists(solrHome) || Files.list(solrHome).findAny().isEmpty then {
        logger.info(s"Solr directory does not exists or is empty. Extracting config at: $solrHome")
        TarGzExtractor.extractResourceTarGz(solrTarGzResource, solrHome)
      }

      System.getProperties.setProperty("solr.data.dir", solrHome.toAbsolutePath.toString)

      val container        = new CoreContainer(solrHome, new Properties())
      container.load()
      val solr: SolrClient = new EmbeddedSolrServer(container, null)

      sys.addShutdownHook {
        try {
          logger.warn("JVM shutdown hook: committing solr")
          collections.foreach(collectionName => solr.commit(collectionName))
          solr.close()
        } catch {
          case e: Exception => logger.error("Error while closing solr", e)
        }
      }

      solr
    }) { solr =>
      IO {
        logger.info("Closing solr client")
        collections.foreach(collectionName => solr.commit(collectionName))
        solr.close()
      }
    }
}
