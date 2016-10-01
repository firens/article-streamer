package articlestreamer.processor.service

import javax.ws.rs.core.HttpHeaders

import articlestreamer.processor.model.TweetPopularity
import articlestreamer.shared.exception.exceptions._
import articlestreamer.shared.twitter.TwitterAuthorizationConfig
import twitter4j.auth.AccessToken
import twitter4j.{Status, TwitterFactory, Twitter}

trait TwitterService extends TwitterAuthorizationConfig {

  val twitter: Twitter  = new TwitterFactory().getInstance()
  twitter.setOAuthConsumer(twitterConfig.getOAuthConsumerKey, twitterConfig.getOAuthConsumerSecret)

  val accessToken: AccessToken = new AccessToken(twitterConfig.getOAuthAccessToken, twitterConfig.getOAuthAccessTokenSecret)
  twitter.setOAuthAccessToken(accessToken)

  def getTweetPopularity(tweetId: Long): Option[TweetPopularity] = {

    try {
      val status: Status = twitter.showStatus(tweetId)
      Some(TweetPopularity(status.getFavoriteCount, status.getRetweetCount))
    } catch {
      case ex: Exception => {
        ex.printNeatStackTrace()
        None
      }
    }

  }


}
