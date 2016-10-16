package articlestreamer.aggregator

import java.sql.Timestamp
import java.util.UUID

import articlestreamer.aggregator.kafka.KafkaProducerWrapper
import articlestreamer.aggregator.scoring.TwitterScoreCalculator
import articlestreamer.aggregator.twitter.TwitterStreamerFactory
import articlestreamer.shared.configuration.ConfigLoader
import articlestreamer.shared.model.TwitterArticle
import org.apache.kafka.clients.producer._
import org.json4s._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.write
import twitter4j.Status

class Aggregator(config: ConfigLoader,
                 producer: KafkaProducerWrapper,
                 scoreCalculator: TwitterScoreCalculator,
                 streamer: TwitterStreamerFactory) {

  def run() {

    val twitterStreamer = streamer.getStreamer(config, tweetHandler(producer), stopHandler(producer))

    println("Starting streaming")
    twitterStreamer.startStreaming()

    sys.addShutdownHook({
      println("Stopping streaming")
      twitterStreamer.stop()
      println("Streaming stopped")

      producer.stopProducer()
    })
  }

  private def tweetHandler(producer: KafkaProducerWrapper): (Status) => Unit = {
    (status: Status) => {

      println(s"Status received: ${status.getCreatedAt}")

      val article = convertToArticle(status)

      implicit val formats = Serialization.formats(NoTypeHints)

      val record = new ProducerRecord[String, String](
        config.kafkaMainTopic,
        s"tweet${status.getId}",
        write(article))

      producer.send(record)
    }
  }

  private def convertToArticle(status: Status): TwitterArticle = {

    val urls: List[String] = status.getURLEntities.map{
      urlEntity => urlEntity.getURL
    }.toList

    val article = TwitterArticle(
      UUID.randomUUID().toString,
      String.valueOf(status.getId),
      new Timestamp(status.getCreatedAt.getTime),
      urls,
      status.getText,
      None)

    val baseScore = scoreCalculator.calculateBaseScore(article)
    article.copy(score = Some(baseScore))

  }

  private def stopHandler(producer: KafkaProducerWrapper): () => Unit = {
    () => producer.stopProducer()
  }

}
