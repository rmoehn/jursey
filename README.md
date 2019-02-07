Limitations
-----------

- There is no way to escape special symbols in hypertext. You cannot use any of
  `[]$` in normal text. This is not a fundamental limitation and could be fixed
  within eight hours.


TODOs
-----

Why do I not do them? YAGNI. I only do what will be useful later. If I expected
end users to use Jursey, I would validate inputs. If I expected to add features
to the core of Jursey, I would clean up the code more. But I don't expect, so I
don't do.

Why are there so many? They are insights that I have while programming and might
not have when I revisit the code weeks later. If one of my YAGNI expectations
turns out wrong, I will open the file to change something and the TODO will
remind me to change something else first. This is also the reason why I leave
them littering the code instead of putting them in an issue tracker.

What about those Notes? When I read other people's code, my first thought is
often: ‘This is stupid.’ Almost as often I realize a few minutes (or days) later
that it was I, who was stupid. When I write a piece of code that I fear to cause
a this-is-stupid thought, I add a note to induce the I-was-stupid response
quickly. Of course, code that seems stupid is at least slightly stupid. The
challenge is to find the level of stupidity that gives optimal long-term
productivity.


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
