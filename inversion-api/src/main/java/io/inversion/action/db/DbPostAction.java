/*
 * Copyright (c) 2015-2019 Rocket Partners, LLC
 * https://github.com/inversion-api
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.inversion.action.db;

import io.inversion.Collection;
import io.inversion.*;
import io.inversion.json.*;
import io.inversion.rql.Term;
import io.inversion.utils.Utils;
import org.apache.commons.collections4.keyvalue.MultiKey;
import org.apache.commons.collections4.map.MultiKeyMap;

import java.util.*;

class DbPostAction<A extends DbPostAction> extends Action<A>  {
    protected boolean collapseAll = false;

    /**
     * When true, forces PUTs to have an resourceKey in the URL
     */
    protected boolean strictRest     = false;
    protected boolean getResponse    = true;

    @Override
    protected List<Rule.RuleMatcher> getDefaultIncludeMatchers(){
        return Utils.asList(new RuleMatcher("POST", "{" + Request.COLLECTION_KEY + "}"));
    }

    public static String nextPath(String path, String next) {
        return Utils.empty(path) ? next : path + "." + next;
    }


    public void run(Request req, Response res) throws ApiException {
        if (req.isMethod("PUT", "POST")) {
            upsert(req, res);
        } else if (req.isMethod("PATCH")) {
            patch(req, res);
        } else {
            throw ApiException.new400BadRequest("Method '%' is not supported by RestPostHandler");
        }

    }

    /**
     * Unlike upsert for POST/PUT, this method is specifically NOT recursive for patching
     * nested documents. It will only patch the parent collection/table.
     * <p>
     * TODO: add support for JSON Patch format...maybe
     *
     * @param req the request to run
     * @param res the response to populate
     */
    public void patch(Request req, Response res) throws ApiException {
        JSNode body = req.getJson();

        if (body == null)
            throw ApiException.new400BadRequest("You must pass a JSON body on a {}", req.getMethod());

        //if the caller posted back an Inversion GET style envelope with meta/data sections, unwrap to get to the real body
        if(body.find("meta") instanceof JSNode && body.find("data") instanceof  JSList)
            body = body.findMap("data");

        //if a single cell array was passed in, unwrap to get to the real body
        if(body instanceof JSList && ((JSList)body).size() == 1 && ((JSList)body).get(0) instanceof JSNode)
            body = ((JSList)body).getNode(0);


        if (body.isList()) {
            if (!Utils.empty(req.getResourceKey())) {
                throw ApiException.new400BadRequest("You can't batch '{}' an array of objects to a specific resource url.  You must '{}' them to a collection.", req.getMethod(), req.getMethod());
            }
        } else {
            String href = body.getString("href");
            if (req.getResourceKey() != null) {
                if (href == null)
                    body.put("href", Utils.substringBefore(req.getUrl().toString(), "?"));
                else if (!req.getUrl().toString().startsWith(href))
                    throw ApiException.new400BadRequest("You are PATCHING-ing an resource with a different href property than the resource URL you are PATCHING-ing to.");
            }
        }

        List<String> resourceKeys = req.getCollection().getDb().patch(req.getCollection(), req.getJson().asMapList());

        if (resourceKeys.size() == req.getJson().asMapList().size()) {
            res.withStatus(Status.SC_201_CREATED);
            String location = Chain.buildLink(req.getCollection(), Utils.implode(",", resourceKeys));
            res.withHeader("Location", location);

            if(isGetResponse()){
                Response getResponse = req.getChain().getEngine().service("GET", location);
                res.getJson().put("data", getResponse.data());
            }
        }
        else
        {
            throw ApiException.new404NotFound("One or more of the requested resource could not be found.");
        }

        //TODO: add res.withChanges()
    }

    public void upsert(Request req, Response res) throws ApiException {
        if (strictRest) {
            if (req.isPost() && req.getResourceKey() != null)
                throw ApiException.new404NotFound("You are trying to POST to a specific resource url.  Set 'strictRest' to false to interpret PUT vs POST intention based on presense of 'href' property in passed in JSON");
            if (req.isPut() && req.getResourceKey() == null)
                throw ApiException.new404NotFound("You are trying to PUT to a collection url.  Set 'strictRest' to false to interpret PUT vs POST intention based on presense of 'href' property in passed in JSON");
        }

        Collection   collection = req.getCollection();
        List<Change> changes    = new ArrayList<>();
        List         resourceKeys;
        JSNode       body        = req.getJson();

        swapRefsWithActualReferences(body);

        JSList bodyArr = body.asList();
        Map visited = new HashMap();
        for(int i=0; i<bodyArr.size(); i++){
            swapLogicalDuplicateReferences(collection, (JSNode)bodyArr.get(i), bodyArr, i + "0", visited);
        }


        //if the caller posted back an Inversion GET style envelope with meta/data sections, unwrap to get to the real body
        if(body.find("meta") instanceof JSNode && body.find("data") instanceof  JSList)
            body = body.findNode("data");

        //if a single cell array was passed in, unwrap to get to the real body
        if(body instanceof JSList && ((JSList)body).size() == 1 && ((JSList)body).get(0) instanceof JSNode)
            body = ((JSList)body).getNode(0);


        boolean     collapseAll = "true".equalsIgnoreCase(req.getUrl().getParam("collapseAll"));
        String collapseStr = req.getUrl().getParam("collapse");
        Set<String> collapses   = collapseStr == null ? new HashSet<>() : Utils.asSet(Utils.explode(",", collapseStr));

        if (collapseAll || collapses.size() > 0) {
            body = JSParser.asJSNode(body.toString());
            collapse(body, collapseAll, collapses, "");
        }

        if (body instanceof JSList) {
            if (!Utils.empty(req.getResourceKey())) {
                throw ApiException.new400BadRequest("You can't batch '{}' an array of objects to a specific resource url.  You must '{}' them to a collection.", req.getMethod(), req.getMethod());
            }
            resourceKeys = upsert(req, collection, (JSList) body);
        } else {
            String href = body.getString("href");
            if (req.isPut() && href != null && req.getResourceKey() != null && !req.getUrl().toString().startsWith(href)) {
                throw ApiException.new400BadRequest("You are PUT-ing an resource with a different href property than the resource URL you are PUT-ing to.");
            }

            resourceKeys = upsert(req, collection, new JSList(body));
        }

        res.withChanges(changes);

        //-- take all of the hrefs and combine into a
        //-- single href for the "Location" header

        //JSList array = new JSList();
        //res.getJson().put("data", array);

        StringBuilder buff = new StringBuilder();
        for (Object key : resourceKeys) {
            String resourceKey = key + "";
            String href        = Chain.buildLink(collection, resourceKey);
            if(href != null){
                res.data().add(new JSMap("href", href));
                String nextId = href.substring(href.lastIndexOf("/") + 1);
                buff.append(",").append(nextId);
            }
        }

        if (buff.length() > 0) {
            res.withStatus(Status.SC_201_CREATED);
            String location = Chain.buildLink(collection, buff.substring(1, buff.length()));
            res.withHeader("Location", location);

            if(isGetResponse()){
                Response getResponse = req.getChain().getEngine().service("GET", location);
                if(getResponse.isSuccess()){
                    res.getJson().put("data", getResponse.data());
                }
                else{
                    getResponse.getJson();
                    res.withBody(getResponse.getBody());
                    res.withStatus(getResponse.getStatus());
                    res.withError(getResponse.getError());
                }

            }
        }
        else
        {
            res.withStatus(Status.SC_204_NO_CONTENT);
        }
    }

    /**
     * README README README README
     * <p>
     * Algorithm:
     * <p>
     * Step 1: Upsert all <code>nodes</code> in this generation...meaning not recursively including
     * key values for all many-to-one foreign keys but excluding all one-to-many and many-to-many
     * key changes...non many-to-one relationships involve modifying other tables that have foreign
     * keys back to this collection's table, not the direct modification of the single table
     * underlying this collection.
     * <p>
     * Step 2: For each relationship POST back through the "front door".  This is the primary
     * recursion that enables nested documents to submitted all at once by client.  Putting
     * this step first ensures that all new objects are POSTed, with their newly created hrefs
     * placed back in the JSON prior to any PUTs that depend on relationship keys to exist.
     * <p>
     * Step 3: PKs generated for child documents which are actually relationship parents, are set
     * as foreign keys back on the parent json (which is actually the one-to-many child)
     * <p>
     * Step 4: Find the key values for all new/kept one-to-many and many-to-many relationships
     * <p>
     * Step 5.1 Upsert all of those new/kept relationships and create the RQL queries needed find
     * all relationships NOT in the upserts.
     * <p>
     * Step 5.2 Null out all now invalid many-to-one foreign keys back
     * and delete all now invalid many-to-many relationships rows.
     *
     * @param req        the request being serviced
     * @param collection the collection be modified
     * @param nodes      the records to update
     * @return the entity keys of all upserted records
     */
    protected List<String> upsert(Request req, Collection collection, JSList nodes) {
        //--
        //--
        //-- Step 1. Upsert this generation including many-to-one relationships where the fk is known
        //--

        System.out.println("UPSERT: " + collection.getName() + ":\r\n" + nodes);
        List<String> returnList = collection.getDb().upsert(collection, nodes);

        for (int i = 0; i < nodes.size(); i++) {
            //-- new records need their newly assigned autogenerated key fields assigned back on them
            JSMap node = (JSMap)nodes.get(i);
            Map<String, Object> row = collection.decodeKeyToJsonNames(returnList.get(i));
            for(String key : row.keySet()){
                node.put(key, row.get(key));
            }

            //-- makes sure any ONE_TO_MANY child nodes that need a key reference back to the parent get it set on them
            for (Relationship rel : collection.getRelationships()) {
                if(rel.isOneToMany() && node.get(rel.getName()) instanceof JSList){
                    Map foreignKey = rel.getInverse().buildForeignKeyFromPrimaryKey(node);
                    node.getList(rel.getName()).asMapList().forEach(child -> child.putAll(foreignKey));
                }
            }
        }

        //--
        //--
        //-- Step 2. recurse by relationship in batch
        //--
        //-- THIS IS THE ONLY RECURSION IN THE ALGORITHM.  IT IS NOT DIRECTLY RECURSIVE. IT
        //-- SENDS THE "CHILD GENERATION" AS A POST BACK TO THE ENGINE WHICH WOULD LAND AT
        //-- THE ACTION (MAYBE THIS ONE) THAT HANDLES THE UPSERT FOR THAT CHILD COLLECTION
        //-- AND ITS DESCENDANTS.
        for (Relationship rel : collection.getRelationships()) {
            Relationship                  inverse    = rel.getInverse();
            LinkedHashMap<String, JSMap> childMap = new LinkedHashMap<>();
            for (JSNode node : nodes.asMapList()) {
                Object value = node.get(rel.getName());

                if (value instanceof JSNode) {
                    for (JSMap child : ((JSNode) value).asMapList()) {
                        String resourceKey = rel.getRelated().encodeKeyFromJsonNames(child);
                        String hashKey     = resourceKey != null ? resourceKey : "_child:" + childMap.size();
                        if(!childMap.containsKey(hashKey))
                            childMap.put(hashKey, child);
                    }
                }

                if (childMap.size() > 0) {
                    String   path = Chain.buildLink(rel.getRelated());

                    JSList childArr = new JSList();
                    for (String key : childMap.keySet()) {
                        childArr.add(childMap.get(key));
                    }

                    Response res  = req.getEngine().post(path, childArr);
                    if (!res.isSuccess())
                        res.rethrow();

                    if(res.data().size() != childMap.size()){
                        throw new ApiException("Can not determine if all children submitted were updated.  Request size = {}.  Response size = {}", childMap.size(), res.data().size());
                    }

                    //-- now get response and set properties BACK on the source from this generation
                    int                 i            = -1;
                    Map<String, JSNode> updatedKeys  = new HashMap();
                    Map<JSNode, String> updatedNodes = new HashMap();
                    for (String childKey : childMap.keySet()) {
                        i++;
                        JSMap newChild = (JSMap) res.data().getMap(i);
                        String newKey = rel.getRelated().encodeKeyFromJsonNames(newChild);
                        if(newKey == null)
                            throw new ApiException("New child key was null {}", newChild);

                        JSMap oldChild = childMap.get(childKey);
                        oldChild.clear();
                        oldChild.putAll(newChild);

                        updatedNodes.put(oldChild, newKey);
                        updatedKeys.put(newKey, oldChild);
                    }
                }
            }
        }

        //--
        //--
        //-- Step 3. sets foreign keys on json parent entities..this is important
        //-- when a JSON parent has a MANY_TO_ONE relationship.  The child object
        //-- may not have even existed on the initial Step 1 Upsert.
        //--
        //-- TODO: you could skip this step if the child pk were known at the start of Step 1.
        //-- TODO: deduplicate the upsert list

        for (Relationship rel : collection.getRelationships()) {
            List<Map> patches = new ArrayList<>();
            if (rel.isManyToOne())//this means we have a FK to the related element's PK
            {
                for (JSNode node : nodes.asMapList()) {
                    Object childObj = node.get(rel.getName());
                    if(!(childObj instanceof JSNode))
                        continue;

                    Map<String, Object> foreignKey = rel.buildForeignKeyFromPrimaryKey((JSMap)childObj);
                    if(foreignKey == null)
                        throw new ApiException("Foreign key should not be null at this point");

                    Map<String, Object> primaryKey = new LinkedHashMap();

                    for(String name : collection.getResourceIndex().getJsonNames()){
                        Object value = node.get(name);
                        if(value == null)
                            throw new ApiException("Primary key field should not be null at this point");
                        primaryKey.put(name, value);
                    }

                    Map<String, Object> patch = new LinkedHashMap<>();
                    patch.putAll(primaryKey);
                    patch.putAll(foreignKey);
                    patches.add(patch);
                }

                if (patches.size() > 0) {
                    //-- don't need to "go back through the front door and PATCH to the engine because we are updating our own collection.
                    collection.getDb().patch(collection, patches);
                }
            }
        }

        //--
        //--
        //-- Step 4: Now find all key values to KEEP for one-to-many and many-to-many relationships
        //-- ... this step just collects them...later, step 5 removes relationships
        //--

        MultiKeyMap<Object, ArrayList> keepRels = new MultiKeyMap<>(); //-- relationship, parentKey, list of childKeys

        for (Relationship rel : collection.getRelationships()) {
            if (rel.isManyToOne())//these were handled in step 1 and 2
                continue;

            for (JSNode node : nodes.asMapList()) {
                if (!(node.get(rel.getName()) instanceof JSList))
                    continue;//-- this property was not passed back in or was not an array of related nodes

                LinkedHashMap<String, Object> foreignKey = buildKey(node, collection.getResourceIndex(), rel.getFkIndex1());

                //LinkedHashMap foreignKey = extractKey(node, rel.getFkIndex1())

                keepRels.put(rel, foreignKey, new ArrayList());//there may not be any child nodes...this has to be added here so it now orphaned children will be found in step 5


                JSList childNodes = node.getList(rel.getName());
                for (int i = 0; childNodes != null && i < childNodes.size(); i++) {
                    JSNode child = (JSNode) childNodes.get(i);
                    if (rel.isOneToMany()) {
                        LinkedHashMap<String, Object> childKey = buildKey(child, rel.getRelated().getResourceIndex());
                        keepRels.get(rel, foreignKey).add(childKey);
                    } else if (rel.isManyToMany()) {
                        LinkedHashMap<String, Object> nodeFk = buildKey(node, collection.getResourceIndex(), rel.getFkIndex1());
                        LinkedHashMap<String, Object> childFk = buildKey(child, rel.getRelated().getResourceIndex(), rel.getFkIndex2());
                        LinkedHashMap<String, Object> linkKey = new LinkedHashMap<>();
                        linkKey.putAll(nodeFk);
                        linkKey.putAll(childFk);
                        keepRels.get(rel, foreignKey).add(linkKey);
                    }
                }
            }
        }

        //--
        //-- Step 5 -
        //--   1. upsert all new and kept relationships
        //--   2. null out all now invalid many-to-one foreign keys
        //--      AND delete all now invalid many-to-many rows
        //--
        //--   To update/delete all now invlaid relationships, we are going to construct
        //--   an RQL query to find all relationships that are NOT in the list we upserted
        //--   in step 4.1
        //--
        //--   The RQL might look like this
        //--
        //--   or(
        //--        and(eq(parentFkX, nodeX.href), not(or(eq(childPk1.1, child.href1.1), eq(childPk1.2, child.href1.2), eq(childPk1.3, child.href1.3)))),
        //--        and(eq(parentFKY, nodeY.href), not(or(eq(childPk2.1, child.href2.1), eq(childPk2.2, child.href2.2)))),
        //--        and(eq(parentFKY, nodeY.href), not(or(eq(childPk2.1, child.href2.1), eq(childPk2.2, child.href2.2)))),
        //--     )
        //--

        for (Map.Entry<MultiKey<? extends Object>, ArrayList> entry : keepRels.entrySet()) {

            Relationship rel       = (Relationship) entry.getKey().getKey(0);
            Map          parentKey = (Map) entry.getKey().getKey(1);
            List<Map>    childKeys = (List) keepRels.get(rel, parentKey);

            System.out.println("rel: " + rel.getName());
            System.out.println("parentKey: " + parentKey);
            System.out.println("child keys: " + childKeys);

            //-- this set will contain the columns we need to update/delete outdated relationships
            Set includesKeys = new HashSet();
            includesKeys.addAll(parentKey.keySet());
            includesKeys.addAll(rel.getRelated().getResourceIndex().getJsonNames());

            Term childNot = Term.term(null, "not");
            Term childOr  = Term.term(childNot, "or");

            List<Map> upserts = new ArrayList();
            for (Map childKey : childKeys) {
                includesKeys.addAll(childKey.keySet());
                childOr.withTerm(asTerm(childKey));
                upserts.add(childKey);
            }

            Collection coll = rel.isOneToMany() ? rel.getRelated() : rel.getFk1Col1().getCollection();

            if (rel.isManyToMany()) {
                //TODO: go through front door?
                System.out.println("updating relationship: " + rel + " -> " + coll + " -> " + upserts);
                coll.getDb().upsert(coll, upserts);
            }

            //-- now find all relationships that are NOT in the group that we just upserted
            //-- they need to be nulled out if many-to-one and deleted if many-to-many

            Map<String, String> queryTerms = new HashMap<>();
            queryTerms.put("limit", "100");
            queryTerms.put("include", Utils.implode(",", includesKeys));

            for (Object parentKeyProp : parentKey.keySet()) {
                queryTerms.put(parentKeyProp.toString(), parentKey.get(parentKeyProp).toString());
            }

            if (childOr.size() > 0) {
                queryTerms.put(childNot.toString(), null);
            }

            String next = Chain.buildLink(coll);
            while (true) {
                log.warn("...looking for one-to-many and many-to-many foreign keys: " + rel + " -> " + queryTerms);

                Response toUnlink = req.getEngine().get(next, queryTerms).assertOk();

                if (toUnlink.data().size() == 0)
                    break;

                toUnlink.dump();

                if (rel.isOneToMany()) {
                    for (JSNode node : toUnlink.data().asMapList()) {
                        for (String prop : rel.getFkIndex1().getJsonNames()) {
                            node.put(prop, null);
                        }
                    }
                    req.getEngine().patch(Chain.buildLink(coll), toUnlink.data()).assertOk();

                }
                //TODO: put back in support for many to many rels recursing through engine
                else if (rel.isManyToMany()) {
                    List resourceKeys = new ArrayList<>();
                    for (JSMap node : toUnlink.data().asMapList()) {
                        String key = coll.encodeKeyFromJsonNames(node);
                        if(key == null)
                            throw new ApiException("Unable to determine the key for a MANY_TO_MANY relationship deletion.");

                        resourceKeys.add(key);
                    }

                    String url = Chain.buildLink(coll) + "/" + Utils.implode(",", resourceKeys);
                    req.getEngine().delete(url);
                }

                if (toUnlink.data().size() < 100)
                    break;
            }
        }

        return returnList;
    }

    LinkedHashMap<String,Object> buildKey(JSNode node, Index index){
        //TODO add support for keys of different lengths
        LinkedHashMap<String, Object> key = new LinkedHashMap<>();
        for(int i=0; i<index.size(); i++){
            Object value = node.get(index.getJsonName(i));
            if(value == null)
                throw new ApiException("Foreign key component can not be null.");
            key.put(index.getJsonName(i), value);
        }
        return key;
    }

    LinkedHashMap<String,Object> buildKey(JSNode node, Index fromIndex, Index toIndex){
        //TODO add support for keys of different lengths
        //TODO what about foreign keys that are out of order

        if(fromIndex == null || toIndex == null)
            throw new ApiException("You can't map an index when one of them is null.");

        LinkedHashMap<String, Object> foreignKey = new LinkedHashMap<>();
        for(int i=0; i<fromIndex.size(); i++){
            Object value = node.get(fromIndex.getJsonName(i));
            if(value == null)
                throw new ApiException("Foreign key component can not be null.");
            foreignKey.put(toIndex.getJsonName(i), value);
        }
        return foreignKey;
    }


    /**
     * Copies a JSNode reference (not a duplicate the actual same JSNode) from its source to where
     * it is referenced as a $ref property.
     *
     * @param root
     */
    protected void swapRefsWithActualReferences(JSNode root){
        root.visit(path -> {
            JSNode node = path.getNode();
            Object refObj = node.get("$ref");
            if(refObj instanceof String){
                String ref = (String)refObj;
                if(ref.startsWith("#")){
                    Object found = root.find(ref);
                    if(found == null)
                        throw ApiException.new400BadRequest("Unable to find $ref ''", ref);
                    path.getParent().getNode().put(path.getProperty(), found);
                }
            }
            return true;
        });
    }

    /**
     * Copies a JSNode body (not a duplicate the actual same JSNode) to a property reference to that body
     * if it exists elsewhere in the document.  For example "author" = "http://host/authors/1234 would be swapped
     * with the body of author 1234 if it existed in the doc.
     */
    protected void swapLogicalDuplicateReferences(Collection childColl, JSNode child, JSNode parent, String parentProp, Map<Collection, Map<String, JSNode>> visited){
        if(child == null)
            return;

        String resourceKey = childColl.encodeKeyFromColumnNames((JSMap)child);
        if(resourceKey != null){
            Map<String, JSNode> keys = visited.get(childColl);
            if(keys == null){
                keys = new HashMap<>();
                visited.put(childColl, keys);
            }

            JSNode existing = keys.get(resourceKey);
            if(existing != null){
                parent.put(parentProp, existing);
                return;
            }else{
                keys.put(resourceKey, child);
            }
        }

        for(Relationship rel : childColl.getRelationships()){
            Object grandchild = child.get(rel.getName());
            if(grandchild instanceof JSList){
                JSList arr = (JSList)grandchild;
                for(int i=0; i<arr.size(); i++){
                    swapLogicalDuplicateReferences(rel.getRelated(), arr.getMap(i), arr, i + "", visited);
                }
            }
            else if(grandchild instanceof JSMap){
                swapLogicalDuplicateReferences(rel.getRelated(), (JSMap)grandchild, child, rel.getName(), visited);
            }
        }
    }



    Term asTerm(Map row) {
        Term t = null;
        for (Object key : row.keySet()) {
            Object value = row.get(key);

            if (t == null) {
                t = Term.term(null, "eq", key, value);
            } else {
                if (!t.hasToken("and"))
                    t = Term.term(null, "and", t);

                t.withTerm(Term.term(t, "eq", key, value));
            }
        }
        return t;
    }

    public boolean isCollapseAll() {
        return collapseAll;
    }

    public DbPostAction withCollapseAll(boolean collapseAll) {
        this.collapseAll = collapseAll;
        return this;
    }

    public boolean isStrictRest() {
        return strictRest;
    }

    public DbPostAction withStrictRest(boolean strictRest) {
        this.strictRest = strictRest;
        return this;
    }

    public boolean isGetResponse() {
        return getResponse;
    }

    public DbPostAction withGetResponse(boolean expandResponse) {
        this.getResponse = expandResponse;
        return this;
    }


    /*
     * Collapses nested objects so that relationships can be preserved but the fields
     * of the nested child objects are not saved (except for FKs back to the parent
     * object in the case of a ONE_TO_MANY relationship).
     *
     * This is intended to be used as a reciprocal to GetHandler "expands" when
     * a client does not want to scrub their json model before posting changes to
     * the parent document back to the parent collection.
     */
    public static void collapse(JSNode parent, boolean collapseAll, Set collapses, String path) {
//        for (String key : parent.keySet()) {
//            Object value = parent.getValue(key);
//
//            if (collapseAll || collapses.contains(nextPath(path, key))) {
//                if (value instanceof JSList) {
//                    JSList children = (JSList) value;
//                    if (children.size() == 0)
//                        parent.removeValue(key);
//
//                    for (int i = 0; i < children.size(); i++) {
//                        if (children.get(i) == null) {
//                            children.remove(i);
//                            i--;
//                            continue;
//                        }
//
//                        if (children.get(i) instanceof JSList || !(children.get(i) instanceof JSNode)) {
//                            children.remove(i);
//                            i--;
//                            continue;
//                        }
//
//                        JSNode child = children.getNode(i);
//                        for (String key2 : child.keySet()) {
//                            if (!key2.equalsIgnoreCase("href")) {
//                                child.removeValue(key2);
//                            }
//                        }
//
//                        if (child.keySet().size() == 0) {
//
//                            children.remove(i);
//                            i--;
//                            continue;
//                        }
//                    }
//                    if (children.size() == 0)
//                        parent.removeValue(key);
//
//                } else if (value instanceof JSNode) {
//                    JSNode child = (JSNode) value;
//                    for (String key2 : child.keySet()) {
//                        if (!key2.equalsIgnoreCase("href")) {
//                            child.removeValue(key2);
//                        }
//                    }
//                    if (child.keySet().size() == 0)
//                        parent.removeValue(key);
//                }
//            } else if (value instanceof JSList) {
//                JSList children = (JSList) value;
//                for (int i = 0; i < children.size(); i++) {
//                    if (children.get(i) instanceof JSNode && !(children.get(i) instanceof JSList)) {
//                        collapse(children.getNode(i), collapseAll, collapses, nextPath(path, key));
//                    }
//                }
//            } else if (value instanceof JSNode) {
//                collapse((JSNode) value, collapseAll, collapses, nextPath(path, key));
//            }
//
//        }
    }

}
