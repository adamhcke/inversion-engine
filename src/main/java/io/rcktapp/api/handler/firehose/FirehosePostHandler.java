package io.rcktapp.api.handler.firehose;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import com.amazonaws.services.kinesisfirehose.AmazonKinesisFirehose;
import com.amazonaws.services.kinesisfirehose.AmazonKinesisFirehoseClientBuilder;
import com.amazonaws.services.kinesisfirehose.model.ListDeliveryStreamsRequest;
import com.amazonaws.services.kinesisfirehose.model.ListDeliveryStreamsResult;
import com.amazonaws.services.kinesisfirehose.model.PutRecordBatchRequest;
import com.amazonaws.services.kinesisfirehose.model.Record;

import io.forty11.web.js.JSArray;
import io.forty11.web.js.JSObject;
import io.rcktapp.api.Action;
import io.rcktapp.api.Api;
import io.rcktapp.api.ApiException;
import io.rcktapp.api.Chain;
import io.rcktapp.api.Collection;
import io.rcktapp.api.Endpoint;
import io.rcktapp.api.Handler;
import io.rcktapp.api.Request;
import io.rcktapp.api.Response;
import io.rcktapp.api.SC;
import io.rcktapp.api.Table;
import io.rcktapp.api.service.Service;

/**
 * Posts records to a mapped AWS Kinesis Firehose stream. 
 * 
 * When you PUT/POST a:
 * <ul>
 * <li>a JSON object - it is submitted as a single record
 * <li>a JSON array - each element in the array is submitted as a single record.
 * </ul>
 * 
 * Unless <code>prettyPrint</code> is set to <code>true</code> all JSON
 * records are stringified without return characters.
 * 
 * All records are always submitted in batches of up to <code>batchMax</code>.  
 * You can submit more than <code>batchMax</code> to the handler and it will try to
 * send as many batches as required. 
 * 
 * If <code>separator</code> is not null (it is '\n' by default) and the 
 * stringified record does not end in <code>separator</code>,
 * <code>separator</code> will be appended to the record.
 * 
 * The underlying Firehose stream is mapped to the collection name through
 * the FireshoseDb.includeStreams property.
 * 
 * 
 * @author wells
 *
 */
public class FirehosePostHandler implements Handler
{
   protected int     batchMax    = 500;
   protected String  separator   = "\n";
   protected boolean prettyPrint = false;

   @Override
   public void service(Service service, Api api, Endpoint endpoint, Action action, Chain chain, Request req, Response res) throws Exception
   {
      if (!req.isMethod("PUT", "POST"))
         throw new ApiException(SC.SC_400_BAD_REQUEST, "The Firehose handler only supports PUT/POST operations...GET and DELETE don't make sense.");

      String collectionKey = req.getCollectionKey();
      Collection col = api.getCollection(collectionKey, FirehoseDb.class);
      Table table = col.getEntity().getTable();
      String streamName = table.getName();

      AmazonKinesisFirehose firehose = ((FirehoseDb) table.getDb()).getFirehoseClient();

      JSObject body = req.getJson();

      if (body == null)
         throw new ApiException(SC.SC_400_BAD_REQUEST, "Attempting to post an empty body to a Firehose stream");

      if (!(body instanceof JSArray))
         body = new JSArray(body);

      JSArray array = (JSArray) body;

      List<Record> batch = new ArrayList();

      for (int i = 0; i < array.length(); i++)
      {
         Object data = array.get(i);

         if (data == null)
            continue;

         String string = data instanceof JSObject ? ((JSObject) data).toString(prettyPrint) : data.toString();

         if (separator != null && !string.endsWith(separator))
            string += separator;

         batch.add(new Record().withData(ByteBuffer.wrap(string.getBytes())));

         if (i > 0 && i % batchMax == 0)
         {
            firehose.putRecordBatch(new PutRecordBatchRequest().withDeliveryStreamName(streamName).withRecords(batch));
            batch.clear();
         }
      }

      if (batch.size() > 0)
      {
         firehose.putRecordBatch(new PutRecordBatchRequest().withDeliveryStreamName(streamName).withRecords(batch));
      }

      res.setStatus(SC.SC_201_CREATED);
   }
}
