Limitations
-----------

- There is no way to escape special symbols in hypertext. You cannot use any of
  `[]$` in normal text. This is not a fundamental limitation and could be fixed
  within eight hours.


Branches
--------

If you want to have a reliable branch (ie. no force-pushes), use `master`. If
you want to see the newest changes, look at the `dev-X` branch with the highest
X. If you want to study history in detail, look at all the `dev` branches.

My development process:

1. Start task X.
2. `git checkout -b dev-X master` (Until task 9.2 I developed everything on
   branch `dev`.)
3. Make snapshot commits, push often, force-push sometimes.
4. Review changes: `git difftool master`
5. `git co master`
6. `git merge dev-X`
7. Squash and reword as needed.
8. `git push`

Note:

- The commits on `dev-X` are too small and the commits on `master` are too big.
  None of them are ‘logically separate changesets’. This is the nature of
  prototyping.

- Sometimes I push to `master` directly.


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


Use REBL JAR with Leiningen
---------------------------

First download the REBL JAR from http://rebl.cognitect.com/. Then this might
work: https://github.com/eccentric-j/lein-rebl-example Otherwise:

```
cd <directory that contains REBL-0.9.109.jar>
mvn install:install-file \
    -Dfile=REBL-0.9.109.jar \
    -DartifactId=REBL \
    -DgroupId=com.cognitect \
    -Dversion=0.9.109 \
    -Dpackaging=jar \
    -Durl=file:local-mvn \
    -DpomFile=pom.xml
```


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
