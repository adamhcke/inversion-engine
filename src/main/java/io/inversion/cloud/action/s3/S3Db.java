/*
 * Copyright (c) 2015-2019 Inversion.org, LLC
 * https://github.com/inversion-api
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.inversion.cloud.action.s3;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.CopyObjectRequest;
import com.amazonaws.services.s3.model.CopyObjectResult;
import com.amazonaws.services.s3.model.GetObjectMetadataRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.s3.model.S3Object;

import io.inversion.cloud.model.Db;
import io.inversion.cloud.model.Results;
import io.inversion.cloud.model.Table;
import io.inversion.cloud.rql.Term;
import io.inversion.cloud.utils.Rows.Row;
import io.inversion.cloud.utils.Utils;

/**
 * Bucket ~= Table
 * Bucket Object field key value ~= Column
 * Only mapping the key field since it is the only way to query anything within S3,
 * since, as of now, you can't request files by size, or content-type, or some 
 * custom header.
 * 
 * @author kfrankic
 *
 */
public class S3Db extends Db<S3Db>
{
   protected String awsAccessKey = null;
   protected String awsSecretKey = null;
   protected String awsRegion    = null;

   protected String bucket       = null;
   protected String basePath     = null;
   protected String includePaths = null;

   private AmazonS3 client       = null;

   /**
    * @see io.rcktapp.api.Db#bootstrapApi()
    */
   @Override
   protected void startup0()
   {
      AmazonS3 client = getS3Client();

      // get all of the buckets this account has access to.  
      List<Bucket> bucketList = client.listBuckets();

      for (Bucket bucket : bucketList)
      {
         Table table = new Table(this, bucket.getName());
         // Hardcoding 'key' as the only column as there is no useful way to use the other metadata
         // for querying 
         // Other core metadata includes: eTag, size, lastModified, storageClass
         table.makeColumn("key", String.class.getName());
         withTable(table);

         api.makeCollection(table, beautifyCollectionName(table.getName()));
      }
   }

//   public S3Object getDownload(S3Request req)
//   {
//      AmazonS3 client = getS3Client();
//      return client.getObject(new GetObjectRequest(req.getBucket(), req.getKey()));
//   }
//
//   public ObjectMetadata getExtendedMetaData(S3Request req)
//   {
//      String key = req.getKey();
//      String prefix = req.getPrefix();
//
//      AmazonS3 client = getS3Client();
//      return client.getObjectMetadata(new GetObjectMetadataRequest(req.getBucket(), prefix != null ? prefix + key : key));
//   }
//
//   public PutObjectResult saveFile(InputStream inputStream, String bucketName, String key, ObjectMetadata meta)
//   {
//      AmazonS3 client = getS3Client();
//      return client.putObject(new PutObjectRequest(bucketName, key, inputStream, meta));
//   }

   @Override
   public Results<Row> select(Table table, List<Term> columnMappedTerms) throws Exception
   {
      S3DbQuery query = new S3DbQuery(table, columnMappedTerms);
      return query.doSelect();
   }

   @Override
   public void delete(Table table, String entityKey) throws Exception
   {
      // TODO Auto-generated method stub

   }

   @Override
   public String upsert(Table table, Map<String, Object> rows) throws Exception
   {
      // TODO Auto-generated method stub
      return null;
   }

   //   /**
   //    * 
   //    * @param bucketName
   //    * @param size - number of files returned in the listing
   //    * @param startFile - the starting point in which the list begins after.
   //    * @return
   //    */
   //   public ObjectListing getCoreMetaData(S3Request s3Req)
   //   {
   //      String prefix = s3Req.getPrefix();
   //      String key = s3Req.getKey();
   //
   //      if (prefix != null)
   //      {
   //         if (key != null)
   //            key = prefix + key;
   //         else
   //            key = prefix;
   //      }
   //
   //      AmazonS3 client = getS3Client();
   //
   //      ListObjectsRequest req = new ListObjectsRequest();
   //      req.setBucketName(s3Req.getBucket());
   //      req.setMaxKeys(s3Req.getSize()); // TODO fix pagesize...currently always set to 1000 ... tied to 'size' but not 'pagesize'?
   //      req.setDelimiter("/");
   //      req.setMarker(s3Req.getMarker());
   //      req.setPrefix(prefix);
   //
   //      return client.listObjects(req);
   //   }
   //
   //   public CopyObjectResult updateObject(String bucket, String key, String newBucket, String newKey, ObjectMetadata meta)
   //   {
   //      AmazonS3 client = getS3Client();
   //
   //      CopyObjectRequest copyReq = null;
   //
   //      if (meta != null)
   //      {
   //         copyReq = new CopyObjectRequest(bucket, key, newBucket, newKey).withNewObjectMetadata(meta);
   //      }
   //      else
   //      {
   //         // rename or move request
   //         copyReq = new CopyObjectRequest(bucket, key, newBucket, newKey);
   //      }
   //
   //      // TODO if the key and newKey are not equal, (or the bucket and newBucket) delete the old key file
   //      return client.copyObject(copyReq);
   //   }

   public AmazonS3 getS3Client()
   {
      return getS3Client(awsRegion, awsAccessKey, awsSecretKey);
   }

   public AmazonS3 getS3Client(String awsRegion, String awsAccessKey, String awsSecretKey)
   {
      if (this.client == null)
      {
         synchronized (this)
         {
            if (this.client == null)
            {
               awsRegion = Utils.findSysEnvPropStr(getName() + ".awsRegion", awsRegion);
               awsAccessKey = Utils.findSysEnvPropStr(getName() + ".awsAccessKey", awsAccessKey);
               awsSecretKey = Utils.findSysEnvPropStr(getName() + ".awsSecretKey", awsSecretKey);

               AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard();

               if (!Utils.empty(awsRegion))
                  builder.withRegion(awsRegion);

               if (!Utils.empty(awsAccessKey) && !Utils.empty(awsSecretKey))
               {
                  BasicAWSCredentials creds = new BasicAWSCredentials(awsAccessKey, awsSecretKey);
                  builder.withCredentials(new AWSStaticCredentialsProvider(creds));
               }

               client = builder.build();
            }
         }
      }

      return this.client;
   }

   public S3Db withAwsRegion(String awsRegion)
   {
      this.awsRegion = awsRegion;
      return this;
   }

   public S3Db withAwsAccessKey(String awsAccessKey)
   {
      this.awsAccessKey = awsAccessKey;
      return this;
   }

   public S3Db withAwsSecretKey(String awsSecretKey)
   {
      this.awsSecretKey = awsSecretKey;
      return this;
   }

   public S3Db withBucket(String bucket)
   {
      this.bucket = bucket;
      return this;
   }

}
