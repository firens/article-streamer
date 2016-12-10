package articlestreamer.twitterupdater

import articlestreamer.shared.configuration.DefaultConfigLoader
import articlestreamer.shared.kafka.{HalfDayTopicManager, KafkaFactory}
import articlestreamer.shared.scoring.NaiveTwitterScoreCalculator
import articlestreamer.shared.twitter.service.TwitterService
import com.softwaremill.macwire._
import twitter4j.TwitterFactory

object MainApp extends App {

  lazy val configLoader = wire[DefaultConfigLoader]

  lazy val twitterFactory = wire[TwitterFactory]
  lazy val twitterService = wire[TwitterService]
  lazy val consumerFactory = wire[KafkaFactory[String, String]]
  lazy val twitterScoreCalculator = wire[NaiveTwitterScoreCalculator]
  lazy val topicManager = wire[HalfDayTopicManager]

  val scoreUpdater = wire[ScoreUpdater]
  scoreUpdater()

}