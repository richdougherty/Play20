<!--- Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com> -->
# Reactive Streams integration (experimental)

> **Play experimental modules are not ready for production use**. APIs may change. Features may not work properly.

[Reactive Streams](http://www.reactive-streams.org/) is a specification and SPI, still under development, that will allow different stream implementations to connect together. The SPI is quite small, with only a few simple interfaces such as `Publisher` and `Subscriber`.

Play 2.4 provides an **experimental** Reactive Streams integration module that adapts `Future`s, `Promise`s, `Enumerator`s and `Iteratee`s into Reactive Streams' `Publisher`s and `Subscriber`s. There is one method in the other direction too.

## Known issues

* The implementations haven't been fully updated to Reactive Streams v0.4. An example of this is that the `Publisher`s and `Subscriber`s may send or accept an `onComplete` event without a preceding `onSubscribe` event.
* May need to lift `Input` events into the stream to ensure that `Input.EOF` events cannot be lost and to provide proper support for `Input.Empty`.
* No performance tuning has been done.
* Needs support for two-way conversion between all the main stream and iteratee types.
* Documentation is limited.
* Test that the module works from Java.

## Usage

Include the Reactive Streams integration library into your project.

```scala
libraryDependencies += "com.typesafe.play" %% "play-streams-experimental" % "%PLAY_VERSION%"
```

All access to the module is through the [`Streams`](api/scala/index.html#play.play.api.libs.streams.Streams) object.

Here is an example that adapts a `Future` into a single-element `Publisher`.

```scala
val fut: Future[Int] = Future { ... }
val pubr: Publisher[Int] = Streams.futureToPublisher(fut)
```

See the `Streams` object's [API documentation](api/scala/index.html#play.play.api.libs.streams.Streams) for more information.