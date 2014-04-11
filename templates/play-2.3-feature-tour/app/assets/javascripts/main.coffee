require.config {
  paths: {
    page: "./Page"
    controller: "./Controller"
    sumWebSocket: "./SumWebSocket"
    jquery: "../lib/jquery/jquery"
  }
  shim: {
    bootstrap: {
      deps: ["jquery"],
      exports: "$"
    }
    jquery: {
      exports: "$"
    }
  }
}

require ["controller"], (controller) ->

  controller.bind()