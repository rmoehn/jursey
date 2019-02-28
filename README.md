See also:
- [Tutorial and command overview](doc/tutorial.md)
- [Applications](#applications-of-reflection) and [features](doc/feature_demo.clj)
- [Implementation tidbits](doc/implementation.md)
- [Proposal for automation-friendly reflection](doc/design/002-reflection-automation.md)
- [Implications for research and possibilities for future work](doc/so-what-what-now.md)
- [Internal and slightly outdated design summary](doc/design/001-basic-design.md)

Table of contents:

<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->


  - [Applications of reflection](#applications-of-reflection)
  - [Limitations](#limitations)
  - [Getting started](#getting-started)
  - [Using a Docker container](#using-a-docker-container)
  - [Branches](#branches)
  - [TODOs](#todos)
  - [Design](#design)
  - [References](#references)
  - [License](#license)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

Jursey
======

A [question-answering](https://ought.org/projects/factored-cognition) system
modelled after [Patchwork](https://github.com/oughtinc/patchwork). Differences:
- Supports [reflection](doc/feature_demo.clj).
- Equal pointers in a sub-question become separate pointers in the
  respective sub-workspace. This is taken from
  [Affable](https://github.com/oughtinc/affable/tree/a4f53b09bd09bb769801e21775f2e13e3cb23cab#interactions).
- Uses [Datomic](https://www.datomic.com/benefits.html) as its database.


Applications of reflection
--------------------------

- [Ask for clarification of a question](test/clarification-swallows.repl)
- [Diff workspaces](scenarios/08-failed-automation-diff.repl)

If you search the second file and [this](test/clarification-airplane.repl) for
‘ISSUE’, you will find comments about problems with the Jursey model. [This
proposal](doc/design/002-reflection-automation.md) addresses some of them.


Limitations
-----------

Removing the following limitations would only be worthwhile if Jursey was going
to be used by regular users. So far it looks like this won't be the case.

- Jursey is not user-friendly. If your input doesn't follow the rules, the
  output won't make sense to you. The error messages won't be helpful.

- There is no way to escape special symbols in hypertext. You cannot use any of
  `[]$` in normal text. This is not a fundamental limitation and could be fixed
  within eight hours.


Getting started
---------------

(This might not work on Windows. If you run into trouble, let me know and we'll
figure something out.)

(You can also skip this section and [run Jursey in a Docker
container](#Using-a-Docker-container).)

1. [**Install Leiningen**](https://leiningen.org/#install), a build tool for
   Clojure. If you use Homebrew, you can just do `brew install leiningen`.

2. **Get the code and Datomic**. Note that with downloading Datomic you agree to
   the terms of the [Datomic Free Edition
   License](https://my.datomic.com/datomic-free-edition-license.html), which
   also comes with the zip archive.

    ```
    $ git clone https://github.com/rmoehn/jursey.git
    $ cd jursey
    $ wget -O datomic-free-0.9.5703.zip https://my.datomic.com/downloads/free/0.9.5703
    $ unzip datomic-free-0.9.5703.zip
    $ mv datomic-free-0.9.5703.zip datomic
    ```

3. **Install the Datomic JAR to your local Maven repo**. Yes, you need Maven.

    ```
    $ cd datomic
    $ ./bin/maven-install
    ```

4. **Start the Datomic transactor**:

    ```
    # Still in directory `datomic`.
    $ ./bin/transactor config/samples/free-transactor-template.properties &
    ```

   I've only gotten the connection between Jursey and the transactor to work
   when the transactor runs on **Java 8**. If on your machine `java -version`
   prints something about version 11, you have to (temporarily) change your
   `JAVA_HOME`. On my Mac it looks like this:

   ```
   JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_202.jdk/Contents/Home/ bin/transactor config/samples/free-transactor-template.properties &
   ```

5. **Start a Clojure REPL**:

    ```
    $ cd .. # Back to project root.
    $ lein repl
    …
    jursey.repl-ui=> (set-up)
    …
    jursey.repl-ui=> (ask-root "What is your name?")
    …
    ```

   You might get a warning about an illegal access operation. You can ignore it or
   make sure that your `lein` command also runs on Java 8.


Using a Docker container
------------------------

Note that with running the following commands, you implicitly download Datomic
Free and thereby agree to the terms of the [Datomic Free Edition
License](https://my.datomic.com/datomic-free-edition-license.html).

```
$ git clone https://github.com/rmoehn/jursey.git
$ cd jursey
$ docker build -t jursey . && docker run -ti --rm jursey
```

This trades leanness and speed for running Jursey with the fewest number of
commands. I don't know why, but it can take **up to a minute** for the REPL
prompt to appear. Also, I didn't make the Dockerfile according to best
practices, so you end up with a > 900 MB image.



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


Design
------

- Copying hypertext makes equality test for nested structures more complex.
  With persistent data structures one could just test identity.


References
----------

- http://blog.datomic.com/2013/06/component-entities.html


License
-------

See [LICENSE.txt](LICENSE.txt).
