/*
 *  Copyright 2014 Carnegie Mellon University
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package edu.cmu.lti.oaqa.annographix.solr;

import java.util.*;

import com.cedarsoftware.util.DeepEquals;

import org.apache.solr.search.SyntaxError;

/**
 * 
 * A structured-query parse + a storage for the parsed result. This parsing
 * output will later used to open the necessary terms as well
 * as to generate similarity {@link org.apache.lucene.search.similarities.SimScorer}
 * 
 * @author Leonid Boytsov
 *
 */
public class StructQueryParser {
  public enum  FieldType      { FIELD_TEXT, FIELD_ANNOTATION };
  public enum  ConstraintType { CONSTRAINT_PARENT, CONSTRAINT_CONTAINS };
  
  public String CONSTR_PARENT = "parent";
  public String CONSTR_CONTAINS = "contains";
  
  public String PREFIX_TOKEN = "~";
  public String PREFIX_ANNOT = "@";
  
  /**
   * 
   * The constructor parses queries.
   * 
   * @param     query
   * @throws    SyntaxError
   */
  public StructQueryParser(String query) throws SyntaxError {
    /*
     *  All white-spaces should become spaces
     *  TODO: can this replacement be problematic, i.e., 
     *        are there any languages/locales
     *        where white-spaces are valuable, 
     *        i.e., we want to keep them 
     *        rather than replacing by regular spaces?
     */
    query = query.replaceAll("\\s", " "); 
    ArrayList<String> allConstr = new ArrayList<String>();
    for (String tok: query.trim().split("\\s+")) {
      if (tok.charAt(0) == '#') {
        // we will process all the constraints after we are done with lexical entries
        allConstr.add(tok);
      } else {
        // a token or an annotation
        FieldType           type = FieldType.FIELD_TEXT;
        LexicalEntryParse   e = null;
        if (tok.startsWith(PREFIX_TOKEN)) {
          e = procLexicalEntry(PREFIX_TOKEN, tok);
        } else if (tok.startsWith(PREFIX_ANNOT)) {
          type = FieldType.FIELD_ANNOTATION;
          e = procLexicalEntry(PREFIX_ANNOT, tok);
        } else {
          throw new SyntaxError(String.format(
              "Bad token '%s', should start with #, %s, or %s",
              tok, PREFIX_TOKEN, PREFIX_ANNOT));
        }
        addOneElement(e, type);
      }
    }
    
    for (int i = 0; i < mTokens.size(); ++i)
      mEdges.put(i, new ArrayList<Integer>());
    
    /*
     * Now all lexical entries are added (and we fully established
     * the mappings from labels to ids). Thus, we can process
     * constraints.
     */
    for (String tok: allConstr)
      addConstraint(tok);
    
    // Finally, let's compute a number of edges each node is connected with
    compConnectQty();
     
  }
  
  /**
   * 
   * Check if the element lable contains only valid symbols.
   * 
   * @param label
   * @return
   */
  private boolean checkLabelSymbols(String label) {
    return label.indexOf(',') == -1 &&
           label.indexOf(' ') == -1 &&        
           label.indexOf('(') == -1 &&
           label.indexOf(')') == -1;
  }
  
  /**
   * 
   * Parse a token, which comes in a format: <prefix> <optional label> : <lexical entry>.
   * 
   * @param prefix  prefix that defines the type, e.g. @.
   * @param token   full text of a token.
   * @return        a pair (label, lexical entry).
   * 
   * @throws SyntaxError
   */
  private LexicalEntryParse procLexicalEntry(String prefix, String token) throws SyntaxError {
    int pos = token.indexOf(':', prefix.length());
    
    if (pos < 0) {
      throw new SyntaxError(String.format(
          "Bad token '%s', missing colon and/or element label after the prefix '%s'",
          token, prefix));  
    }
    String label = token.substring(prefix.length(), pos);
    
    checkLabelSymbols(label);
    
    return new LexicalEntryParse(label, token.substring(pos + 1));
  }
  
  /**
   * Adds an element to token/constraint/etc arrays.
   * @throws SyntaxError 
   */
  private void addOneElement(LexicalEntryParse e, FieldType type) throws SyntaxError {
    int id = mTokens.size();
    if (!e.mLabel.isEmpty()) {
      if (mLabel2Id.containsKey(e.mLabel)) {
        throw new SyntaxError("Duplicate label: '" + e.mLabel + "'");
      }
      mLabel2Id.put(e.mLabel, id);
    }
    mTokens.add(e.mToken);
    mLabels.add(e.mLabel);
    mTypes.add(type);
    mConnectQty.add(0);
    mConstrType.add(new ArrayList<ConstraintType>());
    mDependId.add(new ArrayList<Integer>());
  }
  
  /**
   * Parse constraint-related info and memorizes it.
   */
  private void addConstraint(String tok) throws SyntaxError {
    assert(tok.startsWith("#"));
    
    String constrFmt = "#<constraint name>(<head element label>,<dependent element label 1>,...<dependent element label N>)";
    if (!tok.endsWith(")")) {
      throw new SyntaxError(String.format("Wrong format for the constraint '%s', expected format: %s",
                                          tok, constrFmt));
    }
    int pos = tok.indexOf('(');
    if (pos == -1) {
      throw new SyntaxError(String.format("Missing '(' in the constraint '%s', expected format: %s",
          tok, constrFmt));      
    }
    String op = tok.substring(1, pos);
    ConstraintType type = ConstraintType.CONSTRAINT_PARENT;
    if (op.equalsIgnoreCase(CONSTR_CONTAINS)) {
      type = ConstraintType.CONSTRAINT_CONTAINS;
    } else if (!op.equalsIgnoreCase(CONSTR_PARENT)) {
      throw new SyntaxError(String.format("Wrong constraint name '%s' in the element '%s'", op, tok));
    }
    // Labels cannot contain commas
    String parts[] = tok.substring(pos + 1, tok.length() - 1).split(",");
    if (parts.length < 2) {
      throw new SyntaxError(String.format(
                  "There should be at least 2 elements between '(' and ')'" + 
                  " in the constraint '%s', expected format %s",
                  tok, constrFmt));
    }
    String headLabel = parts[0].trim();
    Integer headId = mLabel2Id.get(headLabel);
    if (null == headId) {
      throw new SyntaxError(String.format("Cannot find a lexical entry " +
          " for the label '%s', constraint '%s'", headLabel, tok));          
    }
    ArrayList<ConstraintType>   constr = mConstrType.get(headId);
    ArrayList<Integer>          dependIds = mDependId.get(headId);
    
    for (int i = 1; i < parts.length; ++i) {
      String    depLabel = parts[i].trim();
      Integer   depId = mLabel2Id.get(depLabel);
      if (null == depId) {
        throw new SyntaxError(String.format("Cannot find a lexical entry " +
            " for the label '%s', constraint '%s'", depLabel, tok));        
      }
      constr.add(type);
      dependIds.add(depId);

      /*
       * This is a potentially horrible linear-time complexity search
       * in an array. However, for a reasonable-size user query 
       * these arrays are going to be tiny and, in practice, 
       * such a linear search  be as fast as or likely even faster than a hash.
       */
      if (!mEdges.get(depId).contains(headId))
        mEdges.get(depId).add(headId);
      if (!mEdges.get(headId).contains(depId))
        mEdges.get(headId).add(depId);

    }
  }
  
  /**
   * Compute a number of edges each node is connected with.
   */
  private void compConnectQty() {
    for (int i = 0; i < mTokens.size(); ++i) {
      if (!mEdges.get(i).isEmpty()) {
        HashSet<Integer>  visited = new HashSet<Integer>();
        doVisit(i, visited);
        mConnectQty.set(i, visited.size());
      }
    }
  }
  
  private void doVisit(int edgeId, HashSet<Integer> visited) {
    if (visited.contains(edgeId)) return; // never visit twice
    visited.add(edgeId);
    ArrayList<Integer> edgeList = mEdges.get(edgeId);
    for (int i = 0; i < edgeList.size(); ++i)
      doVisit(edgeList.get(i), visited);
  }

  /**
   * 
   * A helper comparison function used only for testing.
   * 
   * @param tokens
   * @param labels
   * @param types
   * @param constrType
   * @param dependId
   * @param connectQty
   * @return true if equal
   */
  boolean compareTo(String tokens_[],
                    String labels_[],
                    String types_[],
                    String constrType_[],
                    String dependId_[],
                    int    connectQty_[]) throws Exception {
    int N = tokens_.length;
    
    ArrayList<String>                     tokens = new ArrayList<String>();
    ArrayList<String>                     labels  = new ArrayList<String>(); 
    ArrayList<FieldType>                  types  = new ArrayList<FieldType>();
    ArrayList<ArrayList<ConstraintType>>  constrType 
                                              = new ArrayList<ArrayList<ConstraintType>>();
    ArrayList<ArrayList<Integer>>         dependId
                                              = new ArrayList<ArrayList<Integer>>();
    ArrayList<Integer>                    connectQty = new ArrayList<Integer>();
    
    if (labels_.length != N) throw new Exception(
                        String.format("labels len (%d) != tokens len (%d), args))", 
                                      N, labels_.length));
    if (types_.length != N) throw new Exception(
        String.format("labels len (%d) != types len (%d), args))", 
                      N, types_.length));
    if (constrType_.length != N) throw new Exception(
        String.format("labels len (%d) != constrType len (%d), args))", 
                      N, constrType_.length));
    if (dependId_.length != N) throw new Exception(
        String.format("dependId len (%d) != dependId len (%d), args))", 
                      N, dependId_.length));
    if (connectQty_.length != N) throw new Exception(
        String.format("labels len (%d) != connectQty len (%d), args))", 
                      N, connectQty_.length));
    
    for (int i = 0; i < N; ++i) {
      tokens.add(tokens_[i]);
      labels.add(labels_[i]);
      types.add(FieldType.valueOf(types_[i]));
      ArrayList<ConstraintType> ct = new ArrayList<ConstraintType>();
      for (String t:constrType_[i].split("[,]")) 
      if (!t.isEmpty()) { 
      // This is quite annoying that "".split(",") returns [""] 
        try {
          ct.add(ConstraintType.valueOf(t));
        } catch (java.lang.IllegalArgumentException e) {
          throw new Exception("Failed to parse the constraint value: '" + t + "'");
        }
      }
      constrType.add(ct);
      ArrayList<Integer> it = new ArrayList<Integer>();
      for (String t:dependId_[i].split("[,]")) 
      if (!t.isEmpty()) { 
      // This is quite annoying that "".split(",") returns [""]         
        try {
          it.add(Integer.valueOf(t));
        } catch (java.lang.IllegalArgumentException e) {
          throw new Exception("Failed to parse the dependent id value: '" + t + "'");
        }
      }
      dependId.add(it);
      connectQty.add(connectQty_[i]);
    }
    
    boolean bTokens = DeepEquals.deepEquals(mTokens, tokens);
    boolean bLabels = DeepEquals.deepEquals(mLabels, labels);
    boolean bTypes  = DeepEquals.deepEquals(mTypes, types);
    boolean bConstrType = DeepEquals.deepEquals(mConstrType, constrType);
    boolean bDependId = DeepEquals.deepEquals(mDependId, dependId);
    boolean bConnectQty = DeepEquals.deepEquals(mConnectQty, connectQty);
    boolean bFinal = bTokens && bLabels && bTypes && bConstrType && 
                     bDependId  && bConnectQty;
    if (!bFinal) {
      System.out.println(String.format("Comparison failed: \n" +
                        "  Tokens      : %b \n" +
                        "  Labels      : %b \n" + 
                        "  Types       : %b \n" + 
                        "  ConstrType  : %b \n" + 
                        "  DependId    : %b \n" + 
                        "  ConnectQty  : %b \n",
                  bTokens, bLabels, bTypes, bConstrType, bDependId, bConnectQty
                        ));
    }
    
    return bFinal;
  }

  private ArrayList<String>                         mTokens = 
                                                        new ArrayList<String>();
  private ArrayList<String>                         mLabels = 
                                                        new ArrayList<String>();
  private ArrayList<FieldType>                      mTypes = 
                                                        new ArrayList<FieldType>();
  private ArrayList<ArrayList<ConstraintType>>      mConstrType =
                                      new ArrayList<ArrayList<ConstraintType>>();
  private ArrayList<ArrayList<Integer>>             mDependId = 
                                      new ArrayList<ArrayList<Integer>>();
  private ArrayList<Integer>                        mConnectQty = new ArrayList<Integer>();
  private HashMap<String, Integer>                  mLabel2Id = new HashMap<String, Integer>();

  private HashMap<Integer, ArrayList<Integer>>      mEdges = 
                                      new HashMap<Integer, ArrayList<Integer>>();
}

class LexicalEntryParse {
  String mLabel;
  public LexicalEntryParse(String mLabel, String mToken) {
    this.mLabel = mLabel;
    this.mToken = mToken;
  }
  String mToken;
}