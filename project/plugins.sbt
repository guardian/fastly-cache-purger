addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.4")
addSbtPlugin("com.gu" % "sbt-riffraff-artifact" % "1.1.4")
addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.8.3")

resolvers += "twitter-repo" at "https://maven.twttr.com"
addSbtPlugin("com.twitter" % "scrooge-sbt-plugin" % "21.8.0")
