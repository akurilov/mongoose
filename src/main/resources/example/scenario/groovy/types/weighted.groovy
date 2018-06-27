package example.scenario.groovy.types

final sharedConcurrency = 100
final itemDataSize = "10KB"
final itemsFile = "weighted_load_example.csv"
final itemOutputPath = "/weighted_load_example"

// prepare (create) the 10000 items on a storage before the test
final preconditionLoad1 = PreconditionLoad
    .config(
        [
            item : [
                data : [
                    size : itemDataSize
                ],
                output : [
                    file : itemsFile,
                    path : itemOutputPath
                ]
            ],
            load : [
                step : [
                    limit : [
                        concurrency : sharedConcurrency,
                        count : 10000
                    ]
                ]
            ]
        ]
    )

// declare the weighted load step instance (20% create operations, 80% read operations)
final weightedLoad1 = WeightedLoad
    .append(
        [
            item : [
                data : [
                    size : itemDataSize
                ],
                output : [
                    path : itemOutputPath
                ]
            ],
            load : [
                generator : [
                    weight : 20
                ],
                step : [
                    limit : [
                        time : 100
                    ]
                ]
            ]
        ]
    )
    .append(
        [
            item : [
                input : [
                    file : itemsFile
                ]
            ],
            load : [
                generator : [
                    recycle : [
                        enabled : true
                    ],
                    weight : 80
                ],
                type : "read"
            ]
        ]
    )

// go
"rm -f $itemsFile".execute().waitFor()
preconditionLoad1.run()
weightedLoad1.run()
