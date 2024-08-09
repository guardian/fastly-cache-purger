# Fastly cache purger

Fastly is our CDN. 

The Fastly cache purger listens for content update messages and uses the Fastly API to decache the affected content paths.

This reactive decaching helps to make content updates visible to end users as quickly as possible.


## Decache messages

Sometimes we need to tell third parties when we have made a publicly visible update to a piece of content
(say decaching an updated article on Twitter or updating Facebook News Tab).

To help with these use cases the cache purger publishes content decached events into the ```fastly-cache-purger-PROD-decached``` SNS queue.

To be notified of content decaches you should create an SQS queue for you application and subscribe it to this SNS topic.

These ```com.gu.fastly.model.event.v1.ContentDecachedEvent``` events are JSON serialized and have this format:

```
    required string contentPath (ie. "/business/2020/something")
    required EventType eventType (ie. EventType.Update | EventType.Delete)
```


If a single content update effects multiple paths (an article with alias paths) then multiple ContentDecachedEvent events 
with different paths will be issued.


### Time delay

Fastly decaches may take time to propagate. 

When creating your SQS queue you may wish to add a delay to account for this propagation delay.


## Lambda concurrency

Lambdas in the same account default to sharing a fixed concurrency pool.

To protect this Lambda from other rouge Lambdas consuming all the available shared concurrency we  set `Reserved concurrency` in the AWS console.

This value should be set to at least the number of Kinesis trigger shards.

## Deployment

This app is currently only deployed to PROD: there is no CODE stack. Also, [the Riff Raff configuration](./riff-raff.yaml) is only set to deploy the lambda updates: it doesn’t update the cloudformation.

There is also no continuous deployment configured in Riff Raff for this app at the moment: since one must manually update the cloudformation, it’s probably safest to let people deploy when they’re ready, so that they can make sure to apply [the cloudformation](./cloudformation.yaml) first.
