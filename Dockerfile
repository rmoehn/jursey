# Credits:
# - https://docs.docker.com/samples/library/clojure/
FROM clojure:lein

ENV DATOMIC_VERSION 0.9.5703
RUN mkdir -p /usr/src/jursey
WORKDIR /usr/src/jursey

# Running Datomic in the same container is not a according to best practices.
# But I'll do it for now to make things easier to ’just run’.
RUN wget -O datomic-free-$DATOMIC_VERSION.zip \
    https://my.datomic.com/downloads/free/$DATOMIC_VERSION
RUN unzip datomic-free-$DATOMIC_VERSION.zip
RUN mv datomic-free-$DATOMIC_VERSION datomic
WORKDIR /usr/src/jursey/datomic
RUN apt-get update && apt-get install -y maven
RUN ./bin/maven-install

WORKDIR /usr/src/jursey
COPY project.clj /usr/src/jursey
RUN lein deps

COPY . /usr/src/jursey
CMD ["./docker-jursey.sh"]
