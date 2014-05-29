/**
 * 
 */
package de.csw.cl.importer.algorithm;

import static util.XMLUtil.NS_XCL2;

import java.io.File;
import java.io.FilenameFilter;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.SortedSet;
import java.util.Stack;
import java.util.TreeSet;

import org.jdom2.Comment;
import org.jdom2.Content;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Parent;

import util.XMLUtil;
import de.csw.cl.importer.model.ConflictingTitlingException;
import de.csw.cl.importer.model.Corpus;
import de.csw.cl.importer.model.Includes;
import de.csw.cl.importer.model.XMLCatalog;

/**
 * @author ralph
 *
 */
public class CLImportationAlgorithm {
	
	private enum ELEMENT_TYPE {Titling, Restrict, Import, include, other}
	
	private File baseDir;
	
	private File resultDir;
	private File includesDir;
	
	private Corpus corpus;
	private Includes includes;
	private XMLCatalog catalog;
	
	// not used for now
	private final Queue<Element> potentiallyPendingImports = new LinkedList<Element>();

	private Stack<String> importHistory;
	
	/**
	 * Constructs a {@link CLImportationAlgorithm} object initialized with a given base file.
	 * If the base file resides in directory inputDir, the following file layout will be used:
	 * <ul>
	 * <li> All xcl files residing in inputDir will be considered the corpus.
	 * <li> The resulting xcl file with the importation closure will be saved to inputDir/../result.
	 * <li> Additional files for inclusion will be saved in inputDir/result/includes.
	 * </ul>
	 * @param inputFile
	 */
	public CLImportationAlgorithm(File corpusDirectory) {

		baseDir = corpusDirectory;
		resultDir = new File(baseDir.getParentFile(), "test-result");
		includesDir = new File(resultDir, "includes");

		includes = new Includes(includesDir);
		catalog = new XMLCatalog(new File(resultDir, "catalog.xml"));
	}
	
	/**
	 * Starts the importation process.
	 * @throws ConflictingTitlingException 
	 * @throws FolderCreationException 
	 */
	public void run() throws ConflictingTitlingException, FolderCreationException {
		
		try {
			loadCorpus();
		} catch (ConflictingTitlingException e) {
//			System.out.println("Error: The corpus includes two conflicting titlings with the same name: " + e.getName() + ". Aborting.");
			throw e;
		}
		
		processImports();
		
		//Iterable<Document> documentsInCorpus = corpus.getDocuments();
		
		if (corpus.size() > 0) {
	        if (!(resultDir.exists() || resultDir.mkdir())) {
	            throw new FolderCreationException("Error creating directory " + resultDir.getAbsolutePath());
	        }
	        for (Document document : corpus.getDocuments()) {
	            XMLUtil.writeXML(document, new File(resultDir, corpus.getOriginalFile(document).getName().replaceAll("myText", "resultText")));
	        }
	        catalog.write();
	        if (includes.size() > 0) {
	            if (!(includesDir.exists() || includesDir.mkdir())) {
	                throw new FolderCreationException("Error creating directory " + includesDir.getAbsolutePath());
	            }
	            includes.writeIncludes();
	        }
        }
	}
	
	/**
	 * Loads the corpus.
	 * @throws ConflictingTitlingException
	 */
	private void loadCorpus() throws ConflictingTitlingException {
		corpus = new Corpus();
		
		File[] files = baseDir.listFiles(new FilenameFilter() {
			public boolean accept(File dir, String name) {
				return name.toLowerCase().endsWith(".xcl");
			}
		});
		
		for (File file : files) {
			Document doc = XMLUtil.readLocalDoc(file);
			corpus.addDocument(doc, file);
		}
	}
	
	/**
	 * Processes all import directives in a depth-first fashion, starting at the
	 * root of the document. Repeats until all imports have been resolved or
	 * only unasserted import directives (nested in titlings) are available.
	 */
	private void processImports() {

		//Iterable<Document> documentsInCorpus = corpus.getDocuments();
	    importHistory = new Stack<String>();
		
		while(true) {
		    System.out.println("Starting a Pass");
			// repeat until a complete traversal does not yield any new import
			// resolutions.
			// TODO: possible performance optimization: remember unexecutable imports hidden in titled texts and process them without having to traverse the whole corpus again.
			List<ElementPair> pendingReplacements = new LinkedList<CLImportationAlgorithm.ElementPair>(); 
			
			for (Document document : corpus.getDocuments()) {
				//visitedElements = new HashSet<Element>();
				processImport(document.getRootElement(), new Stack<String>(), new Stack<String>(), pendingReplacements);
			}
			
			// done
			if (pendingReplacements.isEmpty())
				break;
			
			for (ElementPair pair : pendingReplacements) {
				Parent parent = pair.original.getParent();
				int position = parent.indexOf(pair.original);
				pair.original.detach();
				if (pair.replacement != null) {
					parent.addContent(position, pair.replacement);
				}
			}
			

		}
	}
	
	/**
	 * 
	 * @param e an Import element
	 * @return True if an import has been executed, false otherwise
	 */
	private void processImport(Element e,  Stack<String> includeHistory, Stack<String> restrictHistory,  List<ElementPair> pendingReplacements) {
		
		/*if (visitedElements.contains(e)) {
			return;
		}
		visitedElements.add(e);*/
		
		ELEMENT_TYPE elementType = null;
		try {
			elementType = ELEMENT_TYPE.valueOf(e.getName());
		} catch (IllegalArgumentException plannedException) {
			elementType = ELEMENT_TYPE.other;
		}	
		
		switch (elementType) {
			case Import:
				String name = getName(e);
				Element titling = corpus.getImportableTitling(name);
				if (titling != null) {
					// no titling available for this import (yet):
					// return immediately because Import elements cannot have further Import elements as successors.

					// Otherwise: we have a matching titling. Replace the Import element with the contents of the Titling element.				
					Element newXincludeElement = executeImport(e, titling, restrictHistory);
					
					
					pendingReplacements.add(new ElementPair(e, newXincludeElement));
					
					if (!isXInclude(newXincludeElement)) {
						// duplicate import
    						System.out.println("*** Duplicate: removing " + e);
    						return;
					}		
    					
				    System.out.println("*** Replacing " + e + " with " + newXincludeElement);
				
					List<Element> children = newXincludeElement.getChildren();
					for (Element child : children) {
						// importProcessed is true because we have just performed an import
						processImport(child, includeHistory, restrictHistory, pendingReplacements);
					}
				}
				return;
			case include:
				String xincludeHref = e.getAttributeValue("href");
				if (includeHistory.contains(xincludeHref)){
		            // duplicate include
				    System.out.println("Deleting Duplicate Include");
		            Element xincludeConstruct = new Element("Construct", XMLUtil.NS_XCL2);
		            String commentString = "<include xmlns=\"http://www.w3.org/2001/XInclude\" href=\"";
		            commentString = commentString +  xincludeHref;
		            commentString = commentString +  "\"/>";
		            xincludeConstruct.addContent(new Comment(commentString));
                    pendingReplacements.add(new ElementPair(e, xincludeConstruct));
                    // do not follow this include
				    return;
				}
				
				includeHistory.push(xincludeHref);
				String fileHash = catalog.getFileHash(xincludeHref);
				Element referencedInclude = includes.getInclude(fileHash, null);
				
				// the referenced include root element is supposed to be a Titling
				if (!referencedInclude.getName().equals("Titling")) {
					System.err.println("Include " + xincludeHref + ": Root is not a Titling");
				}
				
				List<Element> children = referencedInclude.getChildren();
				for (Element child : children) {
					processImport(child, includeHistory, restrictHistory, pendingReplacements);
				}
				
				break;
			case Restrict:
				restrictHistory.add(getName(e));
				break;
			case Titling:
				// do not process import directives in titlings (yet).
				return;
			case other:
				// do nothing
				break;
		}
		
		// process children
		
		List<Element> children = e.getChildren();
		for (Element child : children) {
			processImport(child, includeHistory, restrictHistory, pendingReplacements);
		}
		
		// undo history
		switch(elementType) {
        case include:
            includeHistory.pop();
            break;
        case Restrict:
            restrictHistory.pop();
            break;
			default :
			    break;
				
		}
		
	}

	/**
	 * Performs the import of a titling. Replaces the current Import element with an Xinclude element,
	 * adds the imported content to an external file and adds a mapping in the xml catalog. 
	 * @param importElement
	 * @param titledElement
	 * @param restrictHistory
	 * @return the new xml include element or null if a cyclic import was detected. 
	 */
	private Element executeImport(Element importElement, Element titledElement, Stack<String> restrictHistory) {
		String titlingName = getName(importElement);
		
		String includeURI = getXincludeURI(titlingName, restrictHistory);
		

		if (importHistory.contains(includeURI)) {
            // duplicate import 
		    // replace with a text construction and comment
            Element xincludeConstruct = new Element("Construct", XMLUtil.NS_XCL2);
            String commentString = "<include xmlns=\"http://www.w3.org/2001/XInclude\" href=\"";
            commentString = commentString +  includeURI.toString();
            commentString = commentString +  "\"/>";
            xincludeConstruct.addContent(new Comment(commentString));
            return xincludeConstruct;
        }

	      // create a new xinclude element and replace the content of this element with it
        Element xincludeElement = new Element("include", XMLUtil.NS_XINCLUDE);
        xincludeElement.setAttribute("href", includeURI);
        xincludeElement.setAttribute("parse", "xml");

		String hashCode = XMLUtil.getMD5Hash(titledElement);
		
		// put the content of the titled text into a separate file
		titledElement = includes.getInclude(hashCode, titledElement);
		
		// add a mapping to the xml catalog
		catalog.addMapping(includeURI, hashCode);
		
		importHistory.push(includeURI);
		
        return xincludeElement;
	}
	
	
	private String getXincludeURI(String titlingName, Stack<String> restrictHistory) {
		try {
			return XMLUtil.NS_ONTOMAVEN.getURI() + "?uri=" + URLEncoder.encode(titlingName, "UTF-8") + getRestrictionFragment(restrictHistory);
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		return null;
	}
	
	private String getTitlingName(String xincludeURI) {
		try {
			return URLDecoder.decode(xincludeURI.replace(XMLUtil.NS_ONTOMAVEN.getURI() + "?uri=", ""), "UTF-8");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		return null;
	}
	
	private String getRestrictionFragment(Stack<String> restrictHistory) {
		StringBuilder buf = new StringBuilder();
		int domainCounter = 1;
		
		TreeSet<String> restrictHistoryNormalized = new TreeSet<String>(restrictHistory);
		
		for (String restrictName : restrictHistoryNormalized) {
			
			buf.append(";dom");
			buf.append(domainCounter++);
			buf.append('=');
			try {
				buf.append(URLEncoder.encode(restrictName, "UTF-8"));
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			}
		}
		
		return buf.toString();
	}
	
	private boolean isTitling(Content e) {
	    switch(e.getCType()) {
	        case Element : 
	            return ((Element) e).getName().equals("Titling");
            default:
                return false;
	    }
	}

   private boolean isImport(Content e) {
        switch(e.getCType()) {
            case Element : 
                return ((Element) e).getName().equals("Import");
            default:
                return false;
        }
    }

   private boolean isRestrict(Content e) {
       switch(e.getCType()) {
           case Element : 
               return ((Element) e).getName().equals("Restrict");
           default:
               return false;
       }
   }

   private boolean isXInclude(Content e) {
       switch(e.getCType()) {
           case Element : 
               return ((Element) e).getName().equals("include");
           default:
               return false;
       }
   }

	private boolean isComment(Content e) {
	       switch(e.getCType()) {
           case Comment : 
               return true;
           default:
               return false;
       }
	}
	
	/**
	 * Returns the name of a CL element
	 * @param e
	 * @return
	 */
	private String getName(Element e) {
		Element nameElement = e.getChild("Name", NS_XCL2);
		return nameElement == null ? null : nameElement.getAttributeValue("cri");
	}
	
	private class ElementPair {
		
		public ElementPair(Element original, Element replacement) {
			this.original = original;
			this.replacement = replacement;
		}
		
		Element original;
		Element replacement;
	}
	
}
