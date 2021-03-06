package articlestreamer.aggregator.utils

import articlestreamer.shared.BaseSpec
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfter

import scalaj.http.HttpOptions.HttpOption
import scalaj.http.{BaseHttp, HttpRequest, HttpResponse}

class HttpUtilsTest extends BaseSpec with BeforeAndAfter {

  class HttpMock extends BaseHttp

  var http: BaseHttp = _
  var httpUtils: HttpUtils = _

  before {
    http = mock(classOf[HttpMock])
    httpUtils = new HttpUtils(http)
  }

  "A direct URL" should "be simply returned" in {
    val simpleResponse = new HttpResponse[String]("", 200, Map())
    val request = mock(classOf[HttpRequest])
    when(request.options(any[HttpOption](), any())).thenReturn(request)
    when(request.asString).thenReturn(simpleResponse)
    when(http.apply(anyString())).thenReturn(request)

    val endUrl = httpUtils.getEndUrl("http://noredirection")

    endUrl shouldBe Some("http://noredirection")
  }

  "An URL with a single redirection" should "be followed till the end" in {
    val simpleResponse = new HttpResponse[String]("", 200, Map())
    val redirectResponse = new HttpResponse[String]("", 304, Map("Location" -> IndexedSeq("http://final")))
    val request = mock(classOf[HttpRequest])
    when(request.options(any[HttpOption](), any())).thenReturn(request)
    when(request.asString).thenReturn(redirectResponse, simpleResponse)
    when(http.apply(anyString())).thenReturn(request)

    val endUrl = httpUtils.getEndUrl("http://redirection")

    endUrl shouldBe Some("http://final")
  }

  "An URL with multiple redirection" should "be followed till the end" in {
    val simpleResponse = new HttpResponse[String]("", 200, Map())
    val firstRedirectResponse = new HttpResponse[String]("", 304, Map("Location" -> IndexedSeq("http://firstRedirect")))
    val secondRedirectResponse = new HttpResponse[String]("", 304, Map("Location" -> IndexedSeq("http://secondRedirect")))
    val thirdRedirectResponse = new HttpResponse[String]("", 304, Map("Location" -> IndexedSeq("http://final")))
    val request = mock(classOf[HttpRequest])
    when(request.options(any[HttpOption](), any())).thenReturn(request)
    when(request.asString).thenReturn(firstRedirectResponse, secondRedirectResponse, thirdRedirectResponse, simpleResponse)
    when(http.apply(anyString())).thenReturn(request)

    val endUrl = httpUtils.getEndUrl("http://redirection")

    endUrl shouldBe Some("http://final")
  }

  "An URL with multiple redirection ending on error" should "be followed till the end" in {
    val simpleResponse = new HttpResponse[String]("", 200, Map())
    val firstRedirectResponse = new HttpResponse[String]("", 304, Map("Location" -> IndexedSeq("http://firstRedirect")))
    val secondRedirectResponse = new HttpResponse[String]("", 304, Map("Location" -> IndexedSeq("http://secondRedirect")))
    val thirdRedirectResponse = new HttpResponse[String]("", 400, Map())
    val request = mock(classOf[HttpRequest])
    when(request.options(any[HttpOption](), any())).thenReturn(request)
    when(request.asString).thenReturn(firstRedirectResponse, secondRedirectResponse, thirdRedirectResponse, simpleResponse)
    when(http.apply(anyString())).thenReturn(request)

    val endUrl = httpUtils.getEndUrl("http://redirection")

    endUrl shouldBe None
  }

  "An HTTP code" should "be identified properly as redirection code" in {
    httpUtils.isRedirectCode(304) shouldBe true
    httpUtils.isRedirectCode(200) shouldBe false
    httpUtils.isRedirectCode(500) shouldBe false
  }

  "An link that looks like a shortlink" should "be identified as potential shortlink" in {
    httpUtils.isPotentialShortLink("http://shortlink") shouldBe true
    httpUtils.isPotentialShortLink("http://linktoolongtobeqshortlink.com?param2=123456") shouldBe false
  }

}
