package articlestreamer.aggregator

import java.text.SimpleDateFormat
import java.util.TimeZone

import articlestreamer.aggregator.service.URLStoreService
import articlestreamer.aggregator.twitter.{DefaultTwitterStreamerFactory, TwitterStreamer}
import articlestreamer.aggregator.utils.HttpUtils
import articlestreamer.shared.BaseSpec
import articlestreamer.shared.configuration.{ConfigLoader, TwitterConfig}
import articlestreamer.shared.kafka.{HalfDayTopicManager, KafkaProducerWrapper}
import articlestreamer.shared.marshalling.CustomJsonFormats
import articlestreamer.shared.model.TwitterArticle
import articlestreamer.shared.scoring.TwitterScoreCalculator
import org.apache.kafka.clients.producer.ProducerRecord
import org.json4s.jackson.Serialization.read
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito.{never, times, verify, when}
import org.quartz.Scheduler
import org.quartz.impl.StdSchedulerFactory
import org.scalatest.BeforeAndAfter
import org.scalatest.mockito.MockitoSugar.mock
import twitter4j.{Status, URLEntity, User}

import scalaj.http.Http

class AggregatorSpec extends BaseSpec with BeforeAndAfter with CustomJsonFormats {

  val df: SimpleDateFormat = new SimpleDateFormat(dformat)
  df.setTimeZone(TimeZone.getTimeZone("GMT"))

  class TestConfig extends ConfigLoader

  val config = new TestConfig() {
    override val twitterConfig = TwitterConfig(null, null, 1, List("SpamAuthor"), List("AdsDomain"))
  }

  val topicManager = new HalfDayTopicManager(config)
  var kafkaWrapper : KafkaProducerWrapper = _
  var scoreCalculator: TwitterScoreCalculator = _
  var streamer: TwitterStreamer = _
  var scheduler: Scheduler = _
  var urlStore: URLStoreService = _
  var httpUtils: HttpUtils = _

  before {
    kafkaWrapper = mock[KafkaProducerWrapper]
    scoreCalculator = mock[TwitterScoreCalculator]
    streamer = mock[TwitterStreamer]
    scheduler = StdSchedulerFactory.getDefaultScheduler
    urlStore = mock[URLStoreService]
    httpUtils = mock[HttpUtils]
  }

  after {
    scheduler.shutdown()
  }

  trait Fixtures {

  }

  "Aggregator when started" should "begin streaming" in new Fixtures {
    val factory = mock[DefaultTwitterStreamerFactory]
    when(factory.getStreamer(any(), any(), any())).thenReturn(streamer)

    val aggregator = new Aggregator(Http, config, kafkaWrapper, scheduler, scoreCalculator, factory, httpUtils, urlStore)
    aggregator.run()

    verify(streamer, times(1)).startStreaming()
  }

  "Tweet received" should "be converted to an article and sent to kafka" in {
    when(scoreCalculator.calculateBaseScore(any())).thenReturn(10)

    val uRLEntity = mock[URLEntity]
    when(uRLEntity.getExpandedURL).thenReturn("http://anyurl.com")

    when(urlStore.exists(anyString())).thenReturn(false)

    val tweetHandler = captureTweetHandler()

    val status = mock[Status]
    val date = df.parse("01-01-2000 00:00:00")
    when(status.getCreatedAt).thenReturn(date)
    when(status.getURLEntities).thenReturn(List[URLEntity](uRLEntity).toArray)
    when(status.getId).thenReturn(1000l)
    when(status.getText).thenReturn("some content")
    when(status.isRetweet).thenReturn(false)
    when(status.getLang).thenReturn("en")
    val user = mock[User]
    when(user.getScreenName).thenReturn("max")
    when(status.getUser).thenReturn(user)

    val captor: ArgumentCaptor[ProducerRecord[String, String]]  = ArgumentCaptor.forClass(classOf[ProducerRecord[String, String]])
    when(kafkaWrapper.send(captor.capture())).thenReturn(null)

    tweetHandler(status)

    verify(urlStore, times(1)).save("http://anyurl.com")

    val articleRecord = captor.getValue
    articleRecord.key() should startWith ("tweet")
    val articleSent = read[TwitterArticle](articleRecord.value())
    articleSent.content shouldBe "some content"
    articleSent.originalId shouldBe "1000"
    articleSent.score shouldBe Some(10)
    articleSent.links shouldBe List("http://anyurl.com")

  }

  "Any tweet not potentially an article " should "be ignored" in {

    val tweetHandler = captureTweetHandler()

    val status = mock[Status]
    val date = df.parse("01-01-2000 00:00:00")
    when(status.getCreatedAt).thenReturn(date)
    when(status.getURLEntities).thenReturn(Array[URLEntity]())
    when(status.getId).thenReturn(1000l)
    when(status.getText).thenReturn("some content")
    when(status.isRetweet).thenReturn(false)
    when(status.getLang).thenReturn("en")

    val user = mock[User]
    when(user.getScreenName).thenReturn("max")
    when(status.getUser).thenReturn(user)

    val captor: ArgumentCaptor[ProducerRecord[String, String]]  = ArgumentCaptor.forClass(classOf[ProducerRecord[String, String]])
    when(kafkaWrapper.send(captor.capture())).thenReturn(null)

    tweetHandler(status)

    verify(urlStore, never()).save(any())
    verify(kafkaWrapper, never()).send(any())
  }

  "Any tweet containing only known links" should "be ignored" in {
    val uRLEntity = mock[URLEntity]
    when(uRLEntity.getExpandedURL).thenReturn("http://anyurl.com")

    when(urlStore.exists(anyString())).thenReturn(true)

    val tweetHandler = captureTweetHandler()

    val status = mock[Status]
    val date = df.parse("01-01-2000 00:00:00")
    when(status.getCreatedAt).thenReturn(date)
    when(status.getURLEntities).thenReturn(Array[URLEntity](uRLEntity))
    when(status.getId).thenReturn(1000l)
    when(status.getText).thenReturn("some content")
    when(status.isRetweet).thenReturn(false)
    when(status.getLang).thenReturn("en")

    val user = mock[User]
    when(user.getScreenName).thenReturn("max")
    when(status.getUser).thenReturn(user)

    val captor: ArgumentCaptor[ProducerRecord[String, String]]  = ArgumentCaptor.forClass(classOf[ProducerRecord[String, String]])
    when(kafkaWrapper.send(captor.capture())).thenReturn(null)

    tweetHandler(status)

    verify(urlStore, never()).save(any())
    verify(kafkaWrapper, never()).send(any())
  }

  "Any tweet containing only shortlinks" should "have each shortlink followed and the url checked for known ones" in {
    val uRLEntity = mock[URLEntity]
    when(uRLEntity.getExpandedURL).thenReturn("http://anyshorturl")

    when(httpUtils.isPotentialShortLink("http://anyshorturl")).thenReturn(true)
    when(httpUtils.getEndUrl("http://anyshorturl")).thenReturn(Some("http://realurl"))
    when(urlStore.exists("http://realurl")).thenReturn(true)

    val tweetHandler = captureTweetHandler()

    val status = mock[Status]
    val date = df.parse("01-01-2000 00:00:00")
    when(status.getCreatedAt).thenReturn(date)
    when(status.getURLEntities).thenReturn(Array[URLEntity](uRLEntity))
    when(status.getId).thenReturn(1000l)
    when(status.getText).thenReturn("some content")
    when(status.isRetweet).thenReturn(false)
    when(status.getLang).thenReturn("en")

    val user = mock[User]
    when(user.getScreenName).thenReturn("max")
    when(status.getUser).thenReturn(user)

    val captor: ArgumentCaptor[ProducerRecord[String, String]]  = ArgumentCaptor.forClass(classOf[ProducerRecord[String, String]])
    when(kafkaWrapper.send(captor.capture())).thenReturn(null)

    tweetHandler(status)

    verify(urlStore, never()).save(any())
    verify(kafkaWrapper, never()).send(any())
  }

  "All retweets" should "be ignored" in {
    val uRLEntity = mock[URLEntity]
    when(uRLEntity.getExpandedURL).thenReturn("http://anyurl.com")

    val tweetHandler = captureTweetHandler()

    val status = mock[Status]
    val date = df.parse("01-01-2000 00:00:00")
    when(status.getCreatedAt).thenReturn(date)
    when(status.getURLEntities).thenReturn(Array[URLEntity](uRLEntity))
    when(status.getId).thenReturn(1000l)
    when(status.getText).thenReturn("some content")
    when(status.isRetweet).thenReturn(true)
    when(status.getLang).thenReturn("en")
    val user = mock[User]
    when(user.getScreenName).thenReturn("max")
    when(status.getUser).thenReturn(user)

    val captor: ArgumentCaptor[ProducerRecord[String, String]]  = ArgumentCaptor.forClass(classOf[ProducerRecord[String, String]])
    when(kafkaWrapper.send(captor.capture())).thenReturn(null)

    tweetHandler(status)

    verify(kafkaWrapper, never()).send(any())
  }

  "All tweets from ignored authors" should "be ignored" in {
    val uRLEntity = mock[URLEntity]
    when(uRLEntity.getExpandedURL).thenReturn("http://anyurl.com")

    val tweetHandler = captureTweetHandler()

    val status = mock[Status]
    val date = df.parse("01-01-2000 00:00:00")
    when(status.getCreatedAt).thenReturn(date)
    when(status.getURLEntities).thenReturn(Array[URLEntity](uRLEntity))
    when(status.getId).thenReturn(1000l)
    when(status.getText).thenReturn("some content")
    when(status.isRetweet).thenReturn(false)
    when(status.getLang).thenReturn("en")
    val user = mock[User]
    when(user.getScreenName).thenReturn("spamAuthor")
    when(status.getUser).thenReturn(user)

    val captor: ArgumentCaptor[ProducerRecord[String, String]]  = ArgumentCaptor.forClass(classOf[ProducerRecord[String, String]])
    when(kafkaWrapper.send(captor.capture())).thenReturn(null)

    tweetHandler(status)

    verify(kafkaWrapper, never()).send(any())
  }

  "All tweets not containing english" should "be ignored" in {
    val uRLEntity = mock[URLEntity]
    when(uRLEntity.getExpandedURL).thenReturn("http://anyurl.com")

    val tweetHandler = captureTweetHandler()

    val status = mock[Status]
    val date = df.parse("01-01-2000 00:00:00")
    when(status.getCreatedAt).thenReturn(date)
    when(status.getURLEntities).thenReturn(Array[URLEntity](uRLEntity))
    when(status.getId).thenReturn(1000l)
    when(status.getText).thenReturn("some content")
    when(status.isRetweet).thenReturn(false)
    when(status.getLang).thenReturn("fr")

    val user = mock[User]
    when(user.getScreenName).thenReturn("max")
    when(status.getUser).thenReturn(user)

    val captor: ArgumentCaptor[ProducerRecord[String, String]]  = ArgumentCaptor.forClass(classOf[ProducerRecord[String, String]])
    when(kafkaWrapper.send(captor.capture())).thenReturn(null)

    tweetHandler(status)

    verify(kafkaWrapper, never()).send(any())
  }

  def captureTweetHandler(): (Status) => Unit = {
    val captor = ArgumentCaptor.forClass(classOf[(Status) => Unit])
    val factory = mock[DefaultTwitterStreamerFactory]
    when(factory.getStreamer(any(), captor.capture(), any())).thenReturn(streamer)

    val aggregator = new Aggregator(Http, config, kafkaWrapper, scheduler, scoreCalculator, factory, httpUtils, urlStore)
    aggregator.run()
    captor.getValue()
  }

}
