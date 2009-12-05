package com.github.tranchis.xsd2thrift;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.sun.xml.xsom.parser.XSOMParser;
import com.sun.xml.xsom.ForeignAttributes;
import com.sun.xml.xsom.XSAttributeDecl;
import com.sun.xml.xsom.XSAttributeUse;
import com.sun.xml.xsom.XSComplexType;
import com.sun.xml.xsom.XSElementDecl;
import com.sun.xml.xsom.XSModelGroup;
import com.sun.xml.xsom.XSParticle;
import com.sun.xml.xsom.XSSchema;
import com.sun.xml.xsom.XSSchemaSet;
import com.sun.xml.xsom.XSTerm;
import com.sun.xml.xsom.XSType;

public class XSDParser implements ErrorHandler
{
	private File					f;
	private Map<String,Struct>		map;
	private Set<String>				keywords, basicTypes;
	private TreeMap<String, String>	typeMapping;
	
	public XSDParser(String stFile)
	{
		typeMapping = new TreeMap<String,String>();
		init(stFile);
	}
	
	private void init(String stFile)
	{
		this.f = new File(stFile);
		map = new HashMap<String,Struct>();
		keywords = new TreeSet<String>();
		keywords.add("interface");
		keywords.add("is");
		basicTypes = new TreeSet<String>();
		basicTypes.add("string");
		basicTypes.add("binary");
		basicTypes.add("i16");
		basicTypes.add("double");
		typeMapping.put("positiveInteger", "i16");
		typeMapping.put("decimal", "double");
	}

	public XSDParser(String stFile, TreeMap<String,String> typeMapping)
	{
		this.typeMapping = typeMapping;
		init(stFile);
	}
	
	public void parse() throws SAXException, IOException
	{
		XSOMParser parser = new XSOMParser();
		parser.setErrorHandler(this);
//		parser.setEntityResolver(xp);

		parser.parse(f);
		//parser.parseSchema( new File("XHTML.xsd"));
		
		interpretResult(parser.getResult());

		writeMap();
	}
	
	private void writeMap()
	{
		Iterator<Struct>	its;
		Iterator<Field>		itf;
		Struct				st;
		Field				f;
		int					order;
		String				fname, type;
		Set<Struct>			ss;
		Set<String>			declared;
		
		ss = new HashSet<Struct>(map.values());
		declared = new TreeSet<String>(basicTypes);
		
		while(!ss.isEmpty())
		{
//			System.out.println(ss);
			its = map.values().iterator();
			while(its.hasNext())
			{
				st = its.next();
//				if(ss.contains(st))
//				{
//					System.out.println(st.getName() + ": " + st.getTypes());
//				}
				if(ss.contains(st) && declared.containsAll(st.getTypes()))
				{
					System.out.println("struct " + st.getName() + "\n{");
					itf = st.getFields().iterator();
					order = 1;
					while(itf.hasNext())
					{
						f = itf.next();
						fname = f.getName();
						type = f.getType();
						if(keywords.contains(fname))
						{
							fname = "_" + fname;
						}
//						System.out.println(map.keySet());
						if(!map.keySet().contains(type) && !basicTypes.contains(type))
						{
							type = "binary";
						}
						if(f.isRepeat())
						{
							type = "list<" + type + ">";
						}
						System.out.println("\t" + order + " : " + getRequired(f.isRequired()) + " " + type + " " + fname + ",");
						order = order + 1;
					}
					System.out.println("}\n");
					declared.add(st.getName());
					//updateDeclared(declared);
					
					// System.out.println("Removing " + st.getName());
					ss.remove(st);
				}
			}
		}
	}

	private String getRequired(boolean required)
	{
		String res;
		
		if(required)
		{
			res = "required";
		}
		else
		{
			res = "optional";
		}
		
		return res;
	}

	private void interpretResult(XSSchemaSet sset)
	{
		XSSchema	xs;
		
		Iterator<XSSchema> it = sset.iterateSchema();
		while(it.hasNext())
		{
			xs = it.next();
			if(!xs.getTargetNamespace().endsWith("/XMLSchema"))
			{
				Iterator<XSElementDecl> itt = xs.iterateElementDecls();
				while(itt.hasNext())
				{
					XSElementDecl el = itt.next();

					interpretElement(el, sset);				
				}
			}
		}
	}

	private void interpretElement(XSElementDecl el, XSSchemaSet sset)
	{
		Struct	st;

		if(el.getType() instanceof XSComplexType && el.getType() != sset.getAnyType())
		{
			XSComplexType cType = (XSComplexType)el.getType();
			//			if(!cType.isAbstract())
			//			{
			if(map.get(el.getName()) == null)
			{
				st = new Struct(el.getName());
				map.put(el.getName(), st);

				XSType parent = cType;
				while(parent != sset.getAnyType())
				{
					if(parent.isComplexType())
					{
						write(st, parent.asComplexType(), true);
						parent = parent.getBaseType();
					}
				}
				//				}
				
				processInheritance(st, cType, sset);
				st.setParent(cType.getBaseType().getName());
			}
		}
		else
		{
			
		}
	}

	private void write(Struct st, XSComplexType type, boolean goingup)
	{
		XSParticle particle = type.getContentType().asParticle();
		if(particle != null)
		{
			write(st, particle.getTerm()/*, order*/, true);
		}
		Iterator<? extends XSAttributeUse> it = type.getAttributeUses().iterator();
		while(it.hasNext())
		{
			XSAttributeUse att = it.next();
			XSAttributeDecl decl = att.getDecl();
			st.addField(decl.getName(), decl.getType().getName(),
					goingup, false, decl.getFixedValue(), typeMapping);
		}
	}

	private void processInheritance(Struct st, XSComplexType cType, XSSchemaSet sset)
	{
		Iterator<XSType> ity = sset.iterateTypes();
		while(ity.hasNext())
		{
			XSType xt = ity.next();
			if(xt.getBaseType() == cType)
			{
				XSParticle particle = xt.asComplexType().getContentType().asParticle();
				if(particle != null)
				{
					write(st, particle.getTerm(), false);
				}
				
				// System.out.println("Processing inheritance from " + cType + " to " + xt.asComplexType());
				processInheritance(st, xt.asComplexType(), sset);
			}
		}
	}

	private void write(Struct st, XSTerm term, boolean goingup)
	{
		Struct	nested;
		
		if(term != null)
		{
			if(term.isModelGroup())
			{
				XSModelGroup modelGroup = term.asModelGroup();
				XSParticle[] ps = modelGroup.getChildren();
				for(int i = 0;i<ps.length;i++)
				{
					XSParticle p = ps[i];
					term = p.getTerm();
					if(term.isModelGroup())
					{
						write(st, term, goingup);
					}
					else if(term.isElementDecl())
					{
						if(term.asElementDecl().getType().getName() == null)
						{
							nested = createNestedType(term.asElementDecl().getName(), term.asElementDecl().getType().asComplexType());
							st.addField(nested.getName(), null, goingup, p.getMaxOccurs() != 1, term.asElementDecl().getFixedValue(), typeMapping);
						}
						else
						{
							st.addField(term.asElementDecl().getName(), term.asElementDecl().getType().getName(),
								goingup, p.getMaxOccurs() != 1, term.asElementDecl().getFixedValue(), typeMapping);
						}
					}
					//order = order + 1;
				}
			}
		}							
	}

	private Struct createNestedType(String name, XSComplexType type)
	{
		Struct		st;
		XSParticle	xp;
		
		st = new Struct(name);
		map.put(name, st);
		
		write(st, type, true);
		
		return st;
	}

	@Override
	public void error(SAXParseException exception) throws SAXException
	{
		System.out.println(exception.getMessage());
		exception.printStackTrace();
	}

	@Override
	public void fatalError(SAXParseException exception) throws SAXException
	{
		System.out.println(exception.getMessage());
		exception.printStackTrace();
	}

	@Override
	public void warning(SAXParseException exception) throws SAXException
	{
		System.out.println(exception.getMessage());
		exception.printStackTrace();
	}

	/**
	 * @param args
	 * @throws IOException 
	 * @throws SAXException 
	 */
	public static void main(String[] args) throws SAXException, IOException
	{
		XSDParser				xp;
		TreeMap<String,String>	map;
		String					xsd;
		
		map = new TreeMap<String,String>();
		map.put("anyURI", "string");
		map.put("anyType", "string");
		map.put("schema_._type", "string");
		map.put("EAnnotation", "string");
		map.put("EGenericType", "string");
		
		if(args.length == 0)
		{
			// xsd = "/Users/sergio/Documents/Alive/Implementation/EventMetamodel/model/EventModel.Event.xsd";
			xsd = "contrib/shiporder.xsd";
		}
		else
		{
			xsd = args[0];
		}
		
		xp = new XSDParser(xsd, map);
		xp.parse();
	}
}
