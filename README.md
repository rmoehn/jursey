Limitations
-----------

- There is no way to escape special symbols in hypertext. You cannot use any of
  `[]$` in normal text. This is not a fundamental limitation and could be fixed
  within eight hours.


Branches
--------

If you want to have a reliable branch (ie. no force-pushes), use `master`. If
you want to see the newest changes or study history in detail, look at `dev`.

I develop on branch `dev`, where I make many snapshot commits and to which I
force-push sometimes. I then lump changes from `dev` together and apply them to
`master`. The effect is that the commits on `dev` are too small and the commits
on `master` are too big. None of them are ‘logically separate changesets’. This
is the nature of prototyping.


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


Design
------

- Copying hypertext makes equality test for nested structures more complex.
  With persistent data structures one could just test identity.


References
----------

- http://blog.datomic.com/2013/06/component-entities.html
