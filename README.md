# Fastly cache purger

Fastly is our CDN. 

The Fastly cache purger listens for content update messages and uses the Fastly API to decache the effected content paths.

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









