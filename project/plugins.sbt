resolvers += Resolver.bintrayRepo("bbp", "nexus-releases")

addSbtPlugin("ch.epfl.bluebrain.nexus" % "sbt-nexus"     % "0.12.1")
addSbtPlugin("com.eed3si9n"            % "sbt-buildinfo" % "0.7.0")
addSbtPlugin("pl.project13.scala"      % "sbt-jmh"       % "0.3.7")
