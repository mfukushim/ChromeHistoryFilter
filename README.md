# ChromeHistoryFilter

ブラウザの履歴は自省に便利（ネットで調べたTipsや自身の趣味嗜好、無駄時間の理解など）なので
Chromeの履歴を取得し、自身のポータルなどの意味のないURLとキーワードを除去してCSVに出力する。

- 実行方法  
```
sbt assembly
# 単純
java -jar target/scala-2.12/ChromeHistoryFilter-assembly-0.1.jar
# 応用
java -jar target/scala-2.12/ChromeHistoryFilter-assembly-0.1.jar -e assets/urlfilter.csv
```
