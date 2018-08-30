var parentConfig_1 = {
  "storage" : {
    "net" : {
      "http" : {
        "namespace" : "ns1"
      }
    }
  }
};

var fileName = "MultipleFixedUpdateAndSingleFixedRead";

var cmd_1 = new java.lang.ProcessBuilder()
    .command("sh", "-c", "rm -f " + fileName + "-0.csv " + fileName + "-1.csv")
    .inheritIO()
    .start();
cmd_1.waitFor();

PreconditionLoad
    .config(parentConfig_1)
    .config({
      "load" : {
        "step" : {
          "limit" : {
            "count" : 2000
          }
        }
      },
      "item" : {
        "output" : {
          "file" : fileName + "-0.csv"
        }
      }
    })
    .run();

var cmd_2 = new java.lang.ProcessBuilder()
    .command("sh", "-c", "sleep 5")
    .inheritIO()
    .start();
cmd_2.waitFor();

UpdateLoad
    .config(parentConfig_1)
    .config({
      "load" : {
        "op" : {
          "type" : "update"
        }
      },
      "item" : {
        "data" : {
          "ranges" : {
            "fixed" : new java.util.ArrayList([ "2-5", "10-20", "50-100", "200-500", "1000-2000" ])
          }
        },
        "output" : {
          "file" : fileName + "-1.csv"
        },
        "input" : {
          "file" : fileName + "-0.csv"
        }
      }
    })
    .run();

var cmd_3 = new java.lang.ProcessBuilder()
    .command("sh", "-c", "sleep 5")
    .inheritIO()
    .start();
cmd_3.waitFor();

ReadLoad
    .config(parentConfig_1)
    .config({
      "load" : {
        "op" : {
          "type" : "read"
        }
      },
      "item" : {
        "data" : {
          "ranges" : {
            "fixed" : new java.util.ArrayList([ "256-" ])
          },
          "verify" : true
        },
        "input" : {
          "file" : fileName + "-1.csv"
        }
      }
    })
    .run();
