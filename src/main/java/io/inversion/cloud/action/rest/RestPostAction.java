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
package io.inversion.cloud.action.rest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ch.qos.logback.classic.net.SyslogAppender;
import io.inversion.cloud.model.Action;
import io.inversion.cloud.model.Api;
import io.inversion.cloud.model.ApiException;
import io.inversion.cloud.model.JSArray;
import io.inversion.cloud.model.Attribute;
import io.inversion.cloud.model.Change;
import io.inversion.cloud.model.Collection;
import io.inversion.cloud.model.Column;
import io.inversion.cloud.model.Endpoint;
import io.inversion.cloud.model.JSNode;
import io.inversion.cloud.model.Relationship;
import io.inversion.cloud.model.Request;
import io.inversion.cloud.model.Response;
import io.inversion.cloud.model.SC;
import io.inversion.cloud.service.Chain;
import io.inversion.cloud.service.Engine;
import io.inversion.cloud.utils.SqlUtils;
import io.inversion.cloud.utils.Utils;

public class RestPostAction extends Action<RestPostAction>
{
   protected boolean collapseAll    = false;
   protected boolean strictRest     = true;
   protected boolean expandResponse = true;

   public RestPostAction()
   {
      this(null);
   }

   public RestPostAction(String inludePaths)
   {
      this(inludePaths, null, null);
   }

   public RestPostAction(String inludePaths, String excludePaths, String config)
   {
      super(inludePaths, excludePaths, config);
      withMethods("PUT,POST");
   }

   @Override
   public void run(Engine engine, Api api, Endpoint endpoint, Chain chain, Request req, Response res) throws Exception
   {
      if (strictRest)
      {
         if (req.isPost() && req.getEntityKey() != null)
            throw new ApiException(SC.SC_404_NOT_FOUND, "You are trying to POST to a specific entity url.  Set 'strictRest' to false to interpret PUT vs POST intention based on presense of 'href' property in passed in JSON");
         if (req.isPut() && req.getEntityKey() == null)
            throw new ApiException(SC.SC_404_NOT_FOUND, "You are trying to PUT to a collection url.  Set 'strictRest' to false to interpret PUT vs POST intention based on presense of 'href' property in passed in JSON");
      }

      Collection collection = req.getCollection();
      List<Change> changes = new ArrayList();
      List<String> entityKeys = new ArrayList();
      JSNode obj = req.getJson();

      if (obj == null)
         throw new ApiException(SC.SC_400_BAD_REQUEST, "You must pass a JSON body to the PostHandler");

      boolean collapseAll = "true".equalsIgnoreCase(chain.getConfig("collapseAll", this.collapseAll + ""));
      Set<String> collapses = chain.mergeEndpointActionParamsConfig("collapses");

      if (collapseAll || collapses.size() > 0)
      {
         obj = JSNode.parseJsonNode(obj.toString());
         collapse(obj, collapseAll, collapses, "");
      }

      try
      {
         if (obj instanceof JSArray)
         {
            if (!Utils.empty(req.getEntityKey()))
            {
               throw new ApiException(SC.SC_400_BAD_REQUEST, "You can't batch " + req.getMethod() + " an array of objects to a specific resource url.  You must " + req.getMethod() + " them to a collection.");
            }
            entityKeys = upsert(req, collection, (JSArray) obj);
         }
         else
         {
            String href = obj.getString("href");
            if (req.isPut() && href != null && req.getEntityKey() != null && !req.getUrl().toString().startsWith(href))
            {
               throw new ApiException(SC.SC_400_BAD_REQUEST, "You are PUT-ing an entity with a different href property than the entity URL you are PUT-ing to.");
            }

            entityKeys = upsert(req, collection, new JSArray(obj));
         }

         res.withChanges(changes);

         //-- take all of the hrefs and combine into a 
         //-- single href for the "Location" header

         JSArray array = new JSArray();
         res.getJson().put("data", array);

         res.withStatus(SC.SC_201_CREATED);
         StringBuffer buff = new StringBuffer("");
         for (int i = 0; i < entityKeys.size(); i++)
         {
            String entityKey = entityKeys.get(i);

            String href = Chain.buildLink(collection, entityKey, null);

            boolean added = false;

            if (!added)
            {
               array.add(new JSNode("href", href));
            }

            String nextId = href.substring(href.lastIndexOf("/") + 1, href.length());
            buff.append(",").append(nextId);
         }

         if (buff.length() > 0)
         {
            String location = Chain.buildLink(collection, buff.substring(1, buff.length()), null);
            res.withHeader("Location", location);
         }

      }
      finally
      {
         // don't do this anymore, connection will be committed/rollbacked and closed in the Engine class
         //SqlUtils.close(conn);
      }

   }

   protected List<String> upsert(Request req, Collection collection, JSArray nodes) throws Exception
   {
      Map<String, Object> mapped;
      Set copied = new HashSet();
      List<Map<String, Object>> maps = new ArrayList<Map<String, Object>>();
      for (JSNode node : (List<JSNode>) ((JSArray) nodes).asList())
      {
         mapped = new HashMap();
         for (Attribute attr : collection.getEntity().getAttributes())
         {
            String attrName = attr.getName();

            if (collection.getEntity().getRelationship(attrName) != null)
               continue;

            String colName = attr.getColumn().getName();
            if (node.containsKey(attrName))
            {
               copied.add(attrName.toLowerCase());
               copied.add(colName.toLowerCase());

               Object attrValue = node.get(attrName);
               Object colValue = collection.getDb().cast(attr, attrValue);
               mapped.put(colName, colValue);
            }
         }

         for (Relationship rel : collection.getEntity().getRelationships())
         {
            //TODO recursively upsert children first and collapse nested objects back into just an href
         }

         for (Relationship rel : collection.getEntity().getRelationships())
         {
            String attrName = rel.getName();
            copied.add(attrName.toLowerCase());

            if (!node.containsKey(attrName))
               continue;

            if (rel.isOneToMany()) //ONE_TO_MANY - Player.locationId -> Location.id
            {
               Object attrValue = node.remove(attrName);

               if (attrValue instanceof String)
               {
                  String attrStr = (String) attrValue;
                  if (attrStr.indexOf("/") > 0 && !attrStr.endsWith("/"))
                     attrStr = attrStr.substring(attrStr.lastIndexOf("/") + 1, attrStr.length());

                  attrValue = attrStr;
               }
               else if (attrValue != null)
               {
                  throw new ApiException("implementation error");
               }

               //TODO: work on compound key support here, need test case
               Column fkCol = rel.getFk1Col1();
               String fkColName = fkCol.getName();
               copied.add(fkColName.toLowerCase());

               Object colValue = attrValue == null ? null : collection.getDb().cast(fkCol.getType(), attrValue);
               mapped.put(fkColName, colValue);
            }
            else
            {
               //TODO
            }
         }

         for (String key : node.keySet())
         {
            if (!copied.contains(key.toLowerCase()) && !key.equalsIgnoreCase("href"))
            {
               //these fields were posted and may map to table columns but they are not defined as attributes.
               //this is ok for some dynamic backends like dynamo but will cause a problem for others like sql rdbmss.
               mapped.put(key, node.get(key));
            }
         }
         maps.add(mapped);
      }
      return collection.getDb().upsert(collection.getTable(), maps);
   }

   /*
    * Collapses nested objects so that relationships can be preserved but the fields
    * of the nested child objects are not saved (except for FKs back to the parent 
    * object in the case of a MANY_TO_ONE relationship).
    * 
    * This is intended to be used as a reciprocal to GetHandler "expands" when
    * a client does not want to scrub their json model before posting changes to
    * the parent document back to the parent collection.
    */
   public static void collapse(JSNode parent, boolean collapseAll, Set collapses, String path)
   {
      for (String key : (List<String>) new ArrayList(parent.keySet()))
      {
         Object value = parent.get(key);

         if (collapseAll || collapses.contains(nextPath(path, key)))
         {
            if (value instanceof JSArray)
            {
               JSArray children = (JSArray) value;
               if (children.length() == 0)
                  parent.remove(key);

               for (int i = 0; i < children.length(); i++)
               {
                  if (children.get(i) == null)
                  {
                     children.remove(i);
                     i--;
                     continue;
                  }

                  if (children.get(i) instanceof JSArray || !(children.get(i) instanceof JSNode))
                  {
                     children.remove(i);
                     i--;
                     continue;
                  }

                  JSNode child = children.getObject(i);
                  for (String key2 : (List<String>) new ArrayList(child.keySet()))
                  {
                     if (!key2.equalsIgnoreCase("href"))
                     {
                        child.remove(key2);
                     }
                  }

                  if (child.keySet().size() == 0)
                  {

                     children.remove(i);
                     i--;
                     continue;
                  }
               }
               if (children.length() == 0)
                  parent.remove(key);

            }
            else if (value instanceof JSNode)
            {
               JSNode child = (JSNode) value;
               for (String key2 : (List<String>) new ArrayList(child.keySet()))
               {
                  if (!key2.equalsIgnoreCase("href"))
                  {
                     child.remove(key2);
                  }
               }
               if (child.keySet().size() == 0)
                  parent.remove(key);
            }
         }
         else if (value instanceof JSArray)
         {
            JSArray children = (JSArray) value;
            for (int i = 0; i < children.length(); i++)
            {
               if (children.get(i) instanceof JSNode && !(children.get(i) instanceof JSArray))
               {
                  collapse(children.getObject(i), collapseAll, collapses, nextPath(path, key));
               }
            }
         }
         else if (value instanceof JSNode)
         {
            collapse((JSNode) value, collapseAll, collapses, nextPath(path, key));
         }

      }
   }

   Object cast(Column col, Object value)
   {
      return SqlUtils.cast(value, col.getType());
      //      
      //      if (Utils.empty(value))
      //         return null;
      //
      //      String type = col.getType().toUpperCase();
      //
      //      if ((type.equals("BIT") || type.equals("BOOLEAN")) && !(value instanceof Boolean))
      //      {
      //         if (value instanceof String)
      //         {
      //            String str = ((String) value).toLowerCase();
      //            value = str.startsWith("t") || str.startsWith("1");
      //         }
      //      }
      //      if ((type.startsWith("DATE") || type.startsWith("TIME")) && !(value instanceof Date))
      //      {
      //         value = Utils.date(value.toString());
      //      }
      //
      //      return value;
   }

   public boolean isCollapseAll()
   {
      return collapseAll;
   }

   public RestPostAction withCollapseAll(boolean collapseAll)
   {
      this.collapseAll = collapseAll;
      return this;
   }

   public boolean isStrictRest()
   {
      return strictRest;
   }

   public RestPostAction withStrictRest(boolean strictRest)
   {
      this.strictRest = strictRest;
      return this;
   }

   public boolean isExpandResponse()
   {
      return expandResponse;
   }

   public RestPostAction withExpandResponse(boolean expandResponse)
   {
      this.expandResponse = expandResponse;
      return this;
   }

}
