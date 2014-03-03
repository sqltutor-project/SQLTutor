package edu.gatech.sqltutor.rules.lang;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.deri.iris.EvaluationException;
import org.deri.iris.KnowledgeBaseFactory;
import org.deri.iris.api.IKnowledgeBase;
import org.deri.iris.api.basics.IPredicate;
import org.deri.iris.api.basics.IQuery;
import org.deri.iris.api.basics.IRule;
import org.deri.iris.factory.Factory;
import org.deri.iris.storage.IRelation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.akiban.sql.parser.FromTable;
import com.akiban.sql.parser.ResultColumn;
import com.akiban.sql.parser.SelectNode;
import com.akiban.sql.parser.StatementNode;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import edu.gatech.sqltutor.IQueryTranslator;
import edu.gatech.sqltutor.QueryUtils;
import edu.gatech.sqltutor.SQLTutorException;
import edu.gatech.sqltutor.rules.AbstractQueryTranslator;
import edu.gatech.sqltutor.rules.ISQLTranslationRule;
import edu.gatech.sqltutor.rules.ISymbolicTranslationRule;
import edu.gatech.sqltutor.rules.ITranslationRule;
import edu.gatech.sqltutor.rules.Markers;
import edu.gatech.sqltutor.rules.SQLState;
import edu.gatech.sqltutor.rules.SymbolicState;
import edu.gatech.sqltutor.rules.datalog.iris.ERFacts;
import edu.gatech.sqltutor.rules.datalog.iris.ERRules;
import edu.gatech.sqltutor.rules.datalog.iris.IrisUtil;
import edu.gatech.sqltutor.rules.datalog.iris.LearnedPredicates;
import edu.gatech.sqltutor.rules.datalog.iris.SQLFacts;
import edu.gatech.sqltutor.rules.datalog.iris.SQLRules;
import edu.gatech.sqltutor.rules.datalog.iris.SymbolicFacts;
import edu.gatech.sqltutor.rules.datalog.iris.SymbolicRules;
import edu.gatech.sqltutor.rules.er.ERAttribute;
import edu.gatech.sqltutor.rules.er.ERDiagram;
import edu.gatech.sqltutor.rules.er.mapping.ERMapping;
import edu.gatech.sqltutor.rules.symbolic.AttributeLiteralLabelRule;
import edu.gatech.sqltutor.rules.symbolic.PartOfSpeech;
import edu.gatech.sqltutor.rules.symbolic.tokens.AndToken;
import edu.gatech.sqltutor.rules.symbolic.tokens.AttributeListToken;
import edu.gatech.sqltutor.rules.symbolic.tokens.AttributeToken;
import edu.gatech.sqltutor.rules.symbolic.tokens.ISymbolicToken;
import edu.gatech.sqltutor.rules.symbolic.tokens.LiteralToken;
import edu.gatech.sqltutor.rules.symbolic.tokens.LiteralsToken;
import edu.gatech.sqltutor.rules.symbolic.tokens.RootToken;
import edu.gatech.sqltutor.rules.symbolic.tokens.SelectToken;
import edu.gatech.sqltutor.rules.symbolic.tokens.SequenceToken;
import edu.gatech.sqltutor.rules.symbolic.tokens.TableEntityToken;

public class SymbolicFragmentTranslator 
		extends AbstractQueryTranslator implements IQueryTranslator {
	private static final Logger _log = 
		LoggerFactory.getLogger(SymbolicFragmentTranslator.class);
	
	// FIXME temp flag to enable non-logging debug output
	private static final boolean DEBUG = true;
	
	protected ERFacts erFacts = new ERFacts();
	protected SQLFacts sqlFacts = new SQLFacts();
	protected SymbolicFacts symFacts = new SymbolicFacts();
	
	protected ERDiagram erDiagram;
	protected ERMapping erMapping;
	protected boolean withDefaults;
	protected boolean defaultsAdded;

	public SymbolicFragmentTranslator() {
		this(true);
	}
	
	public SymbolicFragmentTranslator(boolean withDefaults) {
		translationRules = new ArrayList<ITranslationRule>();
		this.withDefaults = withDefaults;
	}
	
	@Override
	protected void computeTranslation() throws SQLTutorException {
		if( erDiagram == null ) throw new SQLTutorException("No ER diagram set.");
		if( erMapping == null ) throw new SQLTutorException("No ER-relational mapping set.");
		erMapping.setDiagram(erDiagram);
		
		if( withDefaults && !defaultsAdded ) {
			translationRules.addAll(makeDefaultRules());
			defaultsAdded = true;
		}
		
		// ER diagram generated once now
		// TODO may need to adjust if updating
		erFacts.generateFacts(erDiagram);
		erFacts.generateFacts(erMapping);
		
		StatementNode statement = parseQuery();
		SelectNode select = QueryUtils.extractSelectNode(statement);
		
		SQLState sqlState = new SQLState();
		sqlState.setErDiagram(erDiagram);
		sqlState.setErMapping(erMapping);
		sqlState.setAst(select);
		sqlState.setSqlFacts(sqlFacts);
		sqlState.setErFacts(erFacts);
		loadStaticRules();
		
		IKnowledgeBase kb = createSQLKnowledgeBase(select, sqlState);
		sqlState.setKnowledgeBase(kb);
		
		sortRules();
		
		// apply analysis rules to discover new facts
		for( ISQLTranslationRule sqlRule: 
				Iterables.filter(translationRules, ISQLTranslationRule.class) ) {
			while( sqlRule.apply(sqlState) ) {
				kb = createSQLKnowledgeBase(select, sqlState); // regenerate as update may be destructive
				sqlState.setKnowledgeBase(kb);
				
				// apply each rule as many times as possible
				// FIXME non-determinism when precedences match?
				_log.debug(Markers.METARULE, "Applied rule: {}", sqlRule);
			}
			
		}
		
		if( _log.isInfoEnabled() )
			_log.info("statement: {}", QueryUtils.nodeToString(statement));
		
		// all non-symbolic facts and rules are now frozen
		Map<IPredicate, IRelation> queryFacts = makeFacts(sqlState);
		queryFacts.putAll(SymbolicRules.getInstance().getFacts());
		staticRules.addAll(SymbolicRules.getInstance().getRules());
		
		// create initial symbolic state
		RootToken symbolic = makeSymbolic();
		_log.info(Markers.SYMBOLIC, "Symbolic state: {}", symbolic);
		
		SymbolicState symState = new SymbolicState(sqlState);
		symState.setSymbolicFacts(symFacts);
		kb = createSymbolicKnowledgeBase(queryFacts, symbolic);
		symState.setKnowledgeBase(kb);
		
		// FIXME remove this eventually
		if( DEBUG ) {
			IrisUtil.dumpFacts(queryFacts);
			symFacts.generateFacts(symbolic, false);
			IrisUtil.dumpFacts(symFacts.getFacts());
			IrisUtil.dumpRules(staticRules);
			
			dumpQuery(kb, Factory.BASIC.createQuery(
				IrisUtil.literal(LearnedPredicates.tableLabel, "?table", "?label", "?source")
			));
			dumpQuery(kb, Factory.BASIC.createQuery(
				IrisUtil.literal(LearnedPredicates.tableInRelationship, "?tref","?rel","?pos","?source")
			));
		}
		
		// perform rewriting rules
		for( ISymbolicTranslationRule metarule: 
				Iterables.filter(translationRules, ISymbolicTranslationRule.class) ) {
			while( metarule.apply(symState) ) {
				kb = createSymbolicKnowledgeBase(queryFacts, symbolic);
				symState.setKnowledgeBase(kb);
				
				// FIXME non-determinism and final output checks
				
				// apply each rule as many times as possible
				// FIXME non-determinism when precedences match?
				_log.debug(Markers.METARULE, "Applied rule: {}", metarule);
				_log.trace(Markers.SYMBOLIC, "New symbolic state: {}", symbolic);
			}
		}
		
		_log.info(Markers.SYMBOLIC, "Final symbolic state: {}", symbolic);
		
		throw new SQLTutorException("FIXME: Not implemented.");
	}
	
	private IKnowledgeBase createSymbolicKnowledgeBase(Map<IPredicate, IRelation> queryFacts, 
			RootToken symbolic) {

		long duration = -System.currentTimeMillis();
		symFacts.generateFacts(symbolic, false);
		Map<IPredicate, IRelation> facts = mergeFacts(queryFacts, symFacts.getFacts());
		
		List<IRule> rules = staticRules;
		
		_log.info("KB creation prep in {} ms.", duration + System.currentTimeMillis());
		
		try {
			duration = -System.currentTimeMillis();
			IKnowledgeBase kb = KnowledgeBaseFactory.createKnowledgeBase(facts, rules);
			_log.info("KB creation in {} ms.", duration + System.currentTimeMillis());
			return kb;
		} catch( EvaluationException e ) {
			throw new SQLTutorException(e);
		}
	}

	private static void dumpQuery(IKnowledgeBase kb, IQuery q) {
		try {
			IRelation rel = kb.execute(q);
			System.out.println(q);
			for( int i = 0; i < rel.size(); ++i ) {
				System.out.println(rel.get(i));
			}
		} catch( EvaluationException e ) {
			e.printStackTrace();
		}
	}
	
	private List<IRule> staticRules;
	private void loadStaticRules() {
		SQLRules sqlRules = SQLRules.getInstance();
		ERRules erRules = ERRules.getInstance();
		
		staticRules = Lists.newArrayList();
		staticRules.addAll(sqlRules.getRules());
		staticRules.addAll(erRules.getRules());
		for( ITranslationRule rule: translationRules ) {
			staticRules.addAll(rule.getDatalogRules());
		}
	}
	
	private static Map<IPredicate, IRelation> mergeFacts(Map<IPredicate, IRelation>... facts) {
		int size = 1;
		for( Map<IPredicate, IRelation> someFacts: facts )
			size += someFacts.size();
		Map<IPredicate, IRelation> mergedFacts = Maps.newHashMapWithExpectedSize(size);
		for( Map<IPredicate, IRelation> someFacts: facts )
			mergedFacts.putAll(someFacts);
		return mergedFacts;
	}
	
	protected Map<IPredicate, IRelation> makeFacts(SQLState state) {
		SQLRules sqlRules = SQLRules.getInstance();
		ERRules erRules = ERRules.getInstance();
		@SuppressWarnings("unchecked")
		Map<IPredicate, IRelation> facts = mergeFacts(		
			sqlFacts.getFacts(),
			sqlRules.getFacts(),
			erFacts.getFacts(),
			erRules.getFacts(),
			state.getRuleFacts()
		);
		return facts;
	}
	
	protected IKnowledgeBase createSQLKnowledgeBase(SelectNode select, SQLState state) {
		long duration = -System.currentTimeMillis();
		sqlFacts.generateFacts(select, true);
		Map<IPredicate, IRelation> facts = makeFacts(state);
		
		List<IRule> rules = staticRules;
		
		_log.info("KB creation prep in {} ms.", duration + System.currentTimeMillis());
		
		try {
			duration = -System.currentTimeMillis();
			IKnowledgeBase kb = KnowledgeBaseFactory.createKnowledgeBase(facts, rules);
			_log.info("KB creation in {} ms.", duration + System.currentTimeMillis());
			return kb;
		} catch( EvaluationException e ) {
			throw new SQLTutorException(e);
		}
	}

	private Collection<ITranslationRule> makeDefaultRules() {
		return Arrays.<ITranslationRule>asList(
			// analysis rules
			new JoinLabelRule(),
			new DefaultTableLabelRule(),
			new DefaultAttributeLabelRule(),
			new DescribingAttributeLabelRule(),
			
			// rewrite rules
			new AttributeLiteralLabelRule()
		);
	}
	
	private RootToken makeSymbolic() {
		this.buildMaps();
		
		RootToken root = new RootToken();
		root.addChild(new SelectToken());
		
		// create an attribute list for each group of columns that go with a table reference
		List<ISymbolicToken> attrLists = Lists.newLinkedList();
		for( Map.Entry<FromTable, Collection<ResultColumn>> entry : 
				fromToResult.asMap().entrySet() ) {
			FromTable fromTable = entry.getKey();
			Collection<ResultColumn> resultColumns = entry.getValue();
			
			SequenceToken seq = new SequenceToken(PartOfSpeech.NOUN_PHRASE);
			
			// list of attributes
			AttributeListToken attrList = new AttributeListToken();
			for( ResultColumn resultColumn: resultColumns ) {
				String attrName = fromTable.getOrigTableName().getTableName() + "." + resultColumn.getExpression().getColumnName(); 
				ERAttribute erAttr = erMapping.getAttribute(attrName);
				if( erAttr == null )
					_log.warn("No attribute for name {}", attrName);
				AttributeToken attr = new AttributeToken(erAttr);
				attrList.addChild(attr);
			}
			
			seq.addChild(attrList);
			
			// "of each" {entity}
			LiteralsToken literals = new LiteralsToken(PartOfSpeech.PREPOSITIONAL_PHRASE);
			LiteralToken of = new LiteralToken("of", PartOfSpeech.PREPOSITION_OR_SUBORDINATING_CONJUNCTION);
			LiteralToken each = new LiteralToken("each", PartOfSpeech.DETERMINER);
			literals.addChild(of);
			literals.addChild(each);
			seq.addChild(literals);
			
			TableEntityToken table = new TableEntityToken(fromTable);
			seq.addChild(table);
			
			attrLists.add(seq);
		}
		
		if( attrLists.size() == 1 ) {
			root.addChild(attrLists.get(0));
		} else {
			AndToken and = new AndToken();
			for( ISymbolicToken attrList: attrLists )
				and.addChild(attrList);
			root.addChild(and);
		}
		
		return root;
	}
	
	@Override
	public void clearResult() {
		super.clearResult();
		sqlFacts.reset();
		erFacts.reset();
		symFacts.reset();
		defaultsAdded = false;
	}

	@Override
	public Object getTranslatorType() {
		return "Symbolic Language Fragments";
	}

	public ERDiagram getERDiagram() {
		return erDiagram;
	}

	public void setERDiagram(ERDiagram erDiagram) {
		this.erDiagram = erDiagram;
		clearResult();
	}

	public ERMapping getERMapping() {
		return erMapping;
	}

	public void setERMapping(ERMapping erMapping) {
		this.erMapping = erMapping;
		clearResult();
	}
}