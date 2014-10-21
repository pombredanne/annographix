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
package edu.cmu.lti.oaqa.annographix.apps;

import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import edu.cmu.lti.oaqa.annographix.solr.DocumentReader;
import edu.cmu.lti.oaqa.annographix.solr.SolrDocumentIndexer;
import edu.cmu.lti.oaqa.annographix.solr.SolrUtils;
import edu.cmu.lti.oaqa.annographix.util.UtilConst;
import edu.cmu.lti.oaqa.annographix.util.XmlHelper;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * An application that reads text files produced by an annotation
 * pipeline and indexes their content using Solr.
 * <p>
 * The first text file contains documents. The document
 * text is enclosed between tags <DOC></DOC> and occupies
 * exactly one line.
 * <p>
 * The second text file contains annotations in Indri format.
 * 
 * @author Leonid Boytsov
 *
 */
public class SolrIndexApp {
  public static String TEXT_FIELD_ARG = "textField";
  public static String ANNOT_FIELD_ARG = "annotField";
  
  static void Usage(String err) {
    System.err.println("Error: " + err);
    System.err.println("Usage: -i <Text File> -a <Annotation File> " +
                       "-u <Target Server URI> " + 
                       " [ -n <Bach Size> default " + batchQty + " ]");

    System.exit(1);
  }

  public static void main(String[] args) {
    Options options = new Options();
    
    options.addOption("t", null, true, "Text File");
    options.addOption("a", null, true, "Annotation File");
    options.addOption("u", null, true, "Solr URI");
    options.addOption("n", null, true, "Batch size");
    options.addOption(OptionBuilder
                        .withLongOpt(TEXT_FIELD_ARG)
                        .withDescription("Text field name")
                        .hasArg()
                          .create()
                      );
    options.addOption(OptionBuilder
                        .withLongOpt(ANNOT_FIELD_ARG)
                        .withDescription("Annotation field name")
                        .hasArg()
                          .create()
                      );    


    CommandLineParser parser = new org.apache.commons.cli.GnuParser(); 
    
    try {
      CommandLine cmd = parser.parse(options, args);
      
      if (cmd.hasOption("t")) {
        docTextFile = cmd.getOptionValue("t");
      } else {
        Usage("Specify Text File");
      }
      
      if (cmd.hasOption("a")) {
        docAnnotFile = cmd.getOptionValue("a");
      } else {
        Usage("Specify Annotation File");
      }
      
      if (cmd.hasOption("u")) {
        solrURI = cmd.getOptionValue("u");
      } else {
        Usage("Specify Solr URI");
      }
      
      if (cmd.hasOption("n")) {
        batchQty = Integer.parseInt(cmd.getOptionValue("n"));
      }
      
      String textFieldName  = UtilConst.DEFAULT_TEXT4ANNOT_FIELD;
      String annotFieldName = UtilConst.DEFAULT_ANNOT_FIELD;
      
      if (cmd.hasOption(TEXT_FIELD_ARG)) {
        textFieldName = cmd.getOptionValue(TEXT_FIELD_ARG);
      }
      if (cmd.hasOption(ANNOT_FIELD_ARG)) {
        annotFieldName = cmd.getOptionValue(ANNOT_FIELD_ARG);
      }
      
      System.out.println(String.format(
                            "Annotated text field: '%s', annotation field: '%s'",
                            textFieldName, annotFieldName));

      parseConfig(solrURI, textFieldName, annotFieldName);  
      
      System.out.println("Config is fine!");
      
      DocumentReader.readDoc(docTextFile, textFieldName, 
                            docAnnotFile, batchQty,
                            new SolrDocumentIndexer(solrURI, 
                                                    textFieldName,
                                                    annotFieldName));
  
    } catch (ParseException e) {
      Usage("Cannot parse arguments");
    } catch(Exception e) {
      System.err.println("Terminating due to an exception: " + e);
      System.exit(1);
    }    
    
  }
  /**
   * The function checks the following: (1) there are no omit* attributes except
   * from those that match a key from the provided key-value pair map ; 
   * (2) The key-value pair map defines mandatory attributes and their values.
   * 
   * @param fieldName        a name of the field.
   * @param fieldNode        an XML node representing the field.
   * @param mandAttrKeyVal   a key-value pair map of mandatory attributes.
   * 
   * @throws                 Exception
   */
  private static void checkFieldAttrs(String fieldName, Node fieldNode, 
                                      HashMap<String, String> mandAttrKeyVal) 
    throws Exception {
    HashMap<String, String> attKeyVal = new HashMap<String, String>();
    
    NamedNodeMap attrs = fieldNode.getAttributes();
    
    if (null == attrs) {
      if (!mandAttrKeyVal.isEmpty()) 
        throw new Exception("Field: " + fieldNode.getLocalName() + 
            " should have attributes");
      return;
    }
          
    // All omit* attributes are disallowed unless they are explicitly allowed
    for (int i = 0; i < attrs.getLength(); ++i) {
      Node e = attrs.item(i);
      String nm = e.getNodeName();
      attKeyVal.put(nm, e.getNodeValue());
      if (nm.startsWith("omit") && !mandAttrKeyVal.containsKey(nm)) {
        throw new Exception("Unexpected attribute, field: '" + fieldName + "' " +  
            " shouldn't have the attribute '" + nm + "'");
      }
    }
    for (Map.Entry<String, String> e: mandAttrKeyVal.entrySet()) {
      String key = e.getKey();
      if (!attKeyVal.containsKey(key)) {
        throw new Exception("Missing attribute, field: " + fieldName  + "' " +  
            " should have an attribute '" + key  + 
            "' value: '" + e.getValue() + "'");
      }
      String expVal = e.getValue();
      String val = attKeyVal.get(key);
      if (val.compareToIgnoreCase(expVal) != 0) {
        throw new Exception("Wrong attribute value, field: '" + fieldName + "' " +  
            "attribute '" + key + "' should have the value '"+expVal+"'");                
      }
    }
  }

  /**
   * This functions performs sanity check of a SOLR instance configuration
   * file. We need to ensure that 1) the field that stores annotations 
   * uses the whitespace tokenizer; 2) the annotated text field stores both
   * offsets and positions. 
   * <p>Ideally, one should be able to parse the config using standard
   * SOLR class: org.apache.solr.schema.IndexSchema.
   * However, this seems to be impossible, because the schema loader tries
   * to read/parse all the files (e.g., a stopword file) mentioned in the schema 
   * definition. These files are not accessible, though, because they clearly
   * "sit" on a SOLR server file system. 
   * 
   * 
   * @param solrURI         a URI of the SOLR instance.
   * @param textFieldName   a name of the text field to be annotated.
   * @param annotFieldName  a name of the field to store annotations.
   * @throws Exception
   */
  private static void parseConfig(String solrURI, 
                                  String textFieldName,
                                  String annotFieldName) throws Exception {    
    String respText = SolrUtils.getSolrSchema(solrURI);
        
    try {
      DocumentBuilder dbld = DocumentBuilderFactory.newInstance().newDocumentBuilder();
      Document doc = dbld.parse(new InputSource(new StringReader(respText)));
      
      Node root = XmlHelper.getNode("schema", doc.getChildNodes());
      Node fields = XmlHelper.getNode("fields", root.getChildNodes());
      Node types  = XmlHelper.getNode("types", root.getChildNodes());
      
      // Let's read which tokenizers are specified for declared field types  
      HashMap<String, String> typeTokenizers = new HashMap<String, String>();
      for (int i = 0; i < types.getChildNodes().getLength(); ++i) {
        Node oneType = types.getChildNodes().item(i);
        
        if (oneType.getAttributes() == null) continue;
        if (!oneType.getNodeName().equalsIgnoreCase("fieldType")) continue;
        Node nameAttr = oneType.getAttributes().getNamedItem("name");
        if (nameAttr != null) {
          String name = nameAttr.getNodeValue();
          
          Node tmp = XmlHelper.getNode("analyzer", oneType.getChildNodes());
          if (tmp != null) {
            tmp = XmlHelper.getNode("tokenizer", tmp.getChildNodes());
            if (tmp != null) {
              NamedNodeMap attrs = tmp.getAttributes();
              if (null == attrs)
                throw new Exception("No attributes found for the tokenizer description, " + 
                                    " type: " + name);  
              Node tokAttr = attrs.getNamedItem("class");
              if (tokAttr != null)
                typeTokenizers.put(name.toLowerCase(),  tokAttr.getNodeValue());
              else throw new Exception("No class specified for the tokenizer description, " + 
                  " type: " + name);
            }
          }       
        }
      }

      // Read a list of fields, check if they are configured properly
      boolean annotFieldPresent = false;
      boolean text4AnnotFieldPresent = false;
      
      for (int i = 0; i < fields.getChildNodes().getLength(); ++i) {
        Node oneField = fields.getChildNodes().item(i);
        
        if (oneField.getAttributes()==null) continue;
        Node nameAttr = oneField.getAttributes().getNamedItem("name");
        if (nameAttr == null) {
          continue;
        }
        
        String fieldName = nameAttr.getNodeValue();

        // This filed must be present, use the whitespace tokenizer, and index positions      
        if (fieldName.equalsIgnoreCase(annotFieldName)) {
          HashMap<String,String> attrVals = new HashMap<String,String>();
          attrVals.put("omitPositions", "false");
          checkFieldAttrs(fieldName, oneField, attrVals);
          annotFieldPresent = true;
          Node typeAttr = oneField.getAttributes().getNamedItem("type");
          
          if (typeAttr != null) {
            String val = typeAttr.getNodeValue();
            String className = typeTokenizers.get(val.toLowerCase());
            if (className == null) {
              throw new Exception("Cannot find the tokenizer class for the field: " 
                                   + fieldName);
            }

            if (!className.equals(UtilConst.ANNOT_FIELD_TOKENIZER)) {
              throw new Exception("The field: '" + annotFieldName + "' " +
                                  " should be configured to use the tokenizer: " +
                                  UtilConst.ANNOT_FIELD_TOKENIZER);
            }
          } else {
            throw new Exception("Missing type for the annotation field: " 
                                + fieldName);
          }
        } else if (fieldName.equalsIgnoreCase(textFieldName)) {
        // This field must be present, and index positions as well as offsets
          text4AnnotFieldPresent = true;
          HashMap<String,String> attrVals = new HashMap<String,String>();
          attrVals.put("omitPositions", "false");
          attrVals.put("storeOffsetsWithPositions", "true");
          checkFieldAttrs(fieldName, oneField, attrVals);
        }
      }
      if (!annotFieldPresent) {
        throw new Exception("Missing field: " + annotFieldName);
      }
      if (!text4AnnotFieldPresent) {
        throw new Exception("Missing field: " + textFieldName);
      }           
    } catch (SAXException e) {      
      System.err.println("Can't parse SOLR response:\n" + respText);
      throw e;
    }
  }


  static String docTextFile = null, docAnnotFile = null, solrURI = null;
  static int    batchQty = 100;
}
