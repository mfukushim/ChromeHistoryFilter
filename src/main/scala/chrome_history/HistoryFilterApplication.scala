package chrome_history

import java.io.File
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.Properties

import com.github.tototoshi.csv.{CSVReader, CSVWriter}
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import scopt.Read
import slick.jdbc.SQLiteProfile.api._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, duration}

/**
 * Chrome history export and filter
 */
object HistoryFilterApplication extends App {

  val excludeWordListSample = Seq(
    "Yahoo!ニュース",
    "Google カレンダー",
    "Yahoo! JAPAN",
    "https://news.yahoo.co.jp/"
  )

  implicit val pathRead: Read[Path] = Read.reads {
    Paths.get(_)
  }
  implicit val dateRead: Read[DateTime] = Read.reads {
    DateTime.parse
  }

  case class HistoryItem(order: Long, id: Long, date: DateTime, title: String, url: String, visitCount: Long, typedCount: Long, transition: String)

  implicit def historyToList(item: HistoryItem): Seq[String] = {
    Seq(item.order.toString, item.id.toString, item.date.toString, item.title, item.url)
  }

  class Urls(tag: Tag) extends Table[(Long, String, String, Long, Long, Long, Long)](tag, "urls") {
    def id = column[Long]("id", O.PrimaryKey) // This is the primary key column
    def url = column[String]("url")

    def title = column[String]("title")

    def visit_count = column[Long]("visit_count")

    def typed_count = column[Long]("typed_count")

    def last_visit_time = column[Long]("last_visit_time")

    def hidden = column[Long]("hidden")

    // Every table needs a * projection with the same type as the table's type parameter
    def * = (id, url, title, visit_count, typed_count, last_visit_time, hidden)
  }

  val urls = TableQuery[Urls]


  case class Config(
                     excludeUrl: Seq[String] = Seq(),
                     input: Option[File] = None,
                     out: Path = Paths.get(System.getProperty("user.home"), "Desktop", s"""browserHistory${DateTime.now().toString("yyyyMMddHHmmss")}.csv"""),
                     silent: Boolean = false,
                     start: DateTime = DateTime.now().minusMonths(1),
                     end: DateTime = DateTime.now(),
                     help: Boolean = false,
                     debug: Boolean = false
                   )

  import scopt.OParser

  val builder = OParser.builder[Config]
  val parser1 = {
    import builder._
    OParser.sequence(
      programName("ChromeHistoryFilter"),
      head("ChromeHistoryFilter", "Chromeの履歴csvから重複情報を削除してチェックしやすい履歴情報に加工する"),
      // option -f, --foo
      opt[File]('e', "exclude")
        .action((x, c) => c.copy(excludeUrl = {
          using(CSVReader.open(x, "sjis"))(rd => {
            rd.all().map(_.head)
          }).fold(Seq[String]())(list => list)
        }
        )
        ).validate(f => {
        if (f.exists() && f.isFile) success else failure("File not found.")
      })
        .text("exclude url and word list."),
      opt[Path]('o', "output").action((x, c) => {
        c.copy(out = x)
      }).text("Output csv file. default write to home dir"),
      opt[DateTime]('s', "start").action((data, conf) => {
        conf.copy(start = data)
      }).text("pickup Start date. default is 1 month ago. e.g. 2010-06-30T01:20"),
      opt[DateTime]('e', "end").action((data, conf) => {
        conf.copy(end = data)
      }).text("pickup end date.default is now. e.g. 2010-06-30T01:20"),
      opt[Unit]('n', "silent").action((_, conf) => {
        conf.copy(silent = true)
      }).text("Silent mode"),
      opt[Unit]("debug").action((_, conf) => conf.copy(debug = true)).hidden(),
      opt[Option[File]]('c', "csv").action((arg, conf) => {
        conf.copy(input = arg)
      }).validate(_.fold(failure("input csv arg not found"))(g => {
        if (g.exists() && g.isFile) success else failure("input csv not found")
      })).text("History csv file if use from file .(default is load from Chrome setting)")
      // more options here...
    )
  }

  // OParser.parse returns Option[Config]
  OParser.parse(parser1, args, Config()) match {
    case Some(config) =>
      doFilter(config)
    case _ =>
    // arguments are bad, error message will have been displayed
  }


  private def doFilter(config: HistoryFilterApplication.Config): Unit = {
    val historyList = config.input match {
      case Some(f) =>
        //  History CSVファイル読み込み フォーマットは 以下を参考
        //  https://chrome.google.com/webstore/detail/export-chrome-history/dihloblpkeiddiaojbagoecedbfpifdj
        val rd = CSVReader.open(f)
        val csv = rd.toStreamWithHeaders.filter(cells => {
          val date = DateTime.parse(cells("date") + " " + cells("time"), DateTimeFormat.forPattern("M/d/yyyy HH:mm:ss"))
          date.compareTo(config.start) >= 0 && date.compareTo(config.end) <= 0
        }).toList.map(cells => HistoryItem(order = cells("order").toLong,
          id = cells("id").toLong, date = DateTime.parse(cells("date") + " " + cells("time"), DateTimeFormat.forPattern("M/d/yyyy HH:mm:ss")),
          title = cells("title"), url = cells("url"),
          visitCount = cells("visitCount").toLong, typedCount = cells("typedCount").toLong, transition = cells("transition")))
        rd.close()
        csv
      case _ =>
        //直接 Chrome History sqlite3 dbをアクセス
        //  どうしてもビジーになるのでテンポラリファイルを作って、終わったら削除の形でやる
        val os = System.getProperty("os.name").toLowerCase
        val filePath = Paths.get(System.getProperty("user.home"),
          if (os.contains("windows")) """AppData\Local\Google\Chrome\User Data\Default\History"""
          else if (os.contains("mac")) "Library/Application Support/Google/Chrome/System Profile/History" else "History")

        val tempFile = Files.createTempFile("chfdummy", null)
        Files.copy(filePath, tempFile, StandardCopyOption.REPLACE_EXISTING)
        assert(Files.exists(tempFile))

        val start = toChromeTick(config.start)
        val end = toChromeTick(config.end)
        val prop = new Properties()
        prop.setProperty("open_mode", "1")
        prop.setProperty("busy_timeout", "5000")

        val csv = using(Database.forURL(url = s"jdbc:sqlite:$tempFile", prop = prop,
          driver = "org.sqlite.JDBC"))(db => {
          val ret = db.run({
            val a = urls.filter(q => q.last_visit_time >= start && q.last_visit_time <= end).result
//            a.statements.foreach(println)
            a
          }).map(f => {
            f.map(item => {
              HistoryItem(order = 0L, id = item._1.toLong, date = chromeTickToDate(item._6),
                title = item._3, url = item._2, visitCount = item._4, typedCount = item._5, transition = "")
            })

          })
          Await.result(ret, scala.concurrent.duration.Duration.apply(30, duration.SECONDS))
        }).get
        Files.deleteIfExists(tempFile)
        assert(!Files.exists(tempFile))
        assert(csv.nonEmpty)
        csv
    }

    val excludeWordList = if (config.debug) excludeWordListSample else config.excludeUrl

    //order	id	date	time	title	url	visitCount	typedCount	transition
    //17	3631	9/13/2019	20:45:32	国際ニュース - Yahoo!ニュース	https://news.yahoo.co.jp/categories/world	418	0	link
    //  urlが同じものは除外
    //  タイトルに特定のキーワードが含まれているものは削除
    //  フィルタ条件は簡易にするために キーワード文字列が先頭に含まれない+キーワード文字列が中に含まれないの両方　改良要
    //  (条件次第で)同じタイトル名は集約(ヒントを取得したいときはタイトル名はまとめたほうがよい)
    //  並び順は変えない→重複除去してソートしなおし
    //  ロジックは複雑にせずにメモリとCPUにまかせてリソース節約は考えずに論理性優先で作る
    val excludeTarget = historyList.filter(data => excludeWordList.exists(data.title.contains(_)) || excludeWordList.exists(data.url.startsWith)).map(_.id).distinct

    val excludedHistory = historyList.filter(historyItem => {
      !excludeTarget.contains(historyItem.id)
    })
    val sortedHistory = excludedHistory.groupBy(_.url).map(a => {
      a._2.head
    }).toList.sortBy(_.order)

    using(CSVWriter.open(config.out.toFile, "sjis"))(wr => {
      sortedHistory.foreach(item => wr.writeRow(item))
    })

    if (!config.silent) println(s"Filtered url csv is ${config.out.toAbsolutePath}")
  }

  //  参考 http://www.mirandora.com/?p=697
  def toChromeTick(date: DateTime): Long = {
    (date.getMillis - new DateTime(1601, 1, 1, 0, 0, 0).getMillis) * 1000L
  }

  def chromeTickToDate(tick: Long) = {
    new DateTime(tick / 1000L + new DateTime(1601, 1, 1, 0, 0, 0).getMillis)
  }

  //  参考:http://www.ne.jp/asahi/hishidama/home/tech/scala/sample/using.html
  def using[A <: {def close()}, B](resource: A)(func: A => B): Option[B] =
    try {
      Some(func(resource)) //成功したら、Someに包んで返す
    } catch {
      case e: Exception => e.printStackTrace()
        None //失敗したら、ログ吐いて、None返す
    } finally {
      if (resource != null) resource.close()
    }
}
