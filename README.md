Limitations
-----------

- Doesn't support hypertext that starts with a link, eg. `[TS] Garp`. This isn't
  a fundamental limitation and would take less than eight hours to fix.

Use Datomic JAR with Leiningen
------------------------------

```
jar xf datomic-free-0.9.5703.jar META-INF/maven/com.datomic/datomic-free/pom.xml
mvn install:install-file \
    -Dfile=datomic-free-0.9.5703.jar \
    -DartifactId=datomic-free \
    -DgroupId=com.datomic \
    -Dversion=0.9.5703 \
    -Dpackaging=jar \
    -Durl=file:local-mvn -DpomFile=META-INF/maven/com.datomic/datomic-free/pom.xml
```
Credits:
- https://stackoverflow.com/a/9917149/5091738
- https://github.com/technomancy/leiningen/blob/master/doc/FAQ.md
- https://groups.google.com/d/msg/datomic/F_NvePmdL4U/lw_IC9ZPAQAJ

If you don't extract and explicitly specify the POM file, Maven will generate a
new one that lacks all the dependencies of the original. I don't know why that
is. There might be a better way to do this.


Start the transactor
--------------------

```
cd datomic
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_181.jdk/Contents/Home/ bin/transactor config/samples/free-transactor-template.properties
```


References
----------

- http://blog.datomic.com/2013/06/component-entities.html
