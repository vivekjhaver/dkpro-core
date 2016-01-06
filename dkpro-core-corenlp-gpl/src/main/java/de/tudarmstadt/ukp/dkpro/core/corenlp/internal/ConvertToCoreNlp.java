/**
 * Copyright 2007-2014
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 */
package de.tudarmstadt.ukp.dkpro.core.corenlp.internal;

import static org.apache.uima.fit.util.JCasUtil.select;
import static org.apache.uima.fit.util.JCasUtil.selectCovered;
import static org.apache.uima.fit.util.JCasUtil.selectFollowing;
import static org.apache.uima.fit.util.JCasUtil.selectPreceding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.apache.uima.cas.CASException;
import org.apache.uima.jcas.JCas;
import org.apache.uima.jcas.cas.FSArray;

import de.tudarmstadt.ukp.dkpro.core.api.lexmorph.type.pos.POS;
import de.tudarmstadt.ukp.dkpro.core.api.ner.type.NamedEntity;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token;
import de.tudarmstadt.ukp.dkpro.core.api.syntax.type.constituent.Constituent;
import de.tudarmstadt.ukp.dkpro.core.api.syntax.type.constituent.ROOT;
import edu.stanford.nlp.ling.CoreAnnotations.*;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.Word;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.process.CoreLabelTokenFactory;
import edu.stanford.nlp.process.PTBEscapingProcessor;
import edu.stanford.nlp.trees.LabeledScoredTreeFactory;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TreeFactory;
import edu.stanford.nlp.trees.TreeCoreAnnotations.TreeAnnotation;
import edu.stanford.nlp.util.CoreMap;

public class ConvertToCoreNlp
{
    private CoreLabelTokenFactory tokenFactory = new CoreLabelTokenFactory();
    
    private boolean ptb3Escaping;
    private List<String> quoteBegin;
    private List<String> quoteEnd;
    
    public boolean isPtb3Escaping()
    {
        return ptb3Escaping;
    }

    public void setPtb3Escaping(boolean aPtb3Escaping)
    {
        ptb3Escaping = aPtb3Escaping;
    }

    public List<String> getQuoteBegin()
    {
        return quoteBegin;
    }

    public void setQuoteBegin(List<String> aQuoteBegin)
    {
        quoteBegin = aQuoteBegin;
    }

    public List<String> getQuoteEnd()
    {
        return quoteEnd;
    }

    public void setQuoteEnd(List<String> aQuoteEnd)
    {
        quoteEnd = aQuoteEnd;
    }

    public Annotation convert(JCas aJCas)
    {
        // Document annotation
        Annotation document = new Annotation(aJCas.getDocumentText());
        
        // Sentences
        List<CoreMap> sentences = new ArrayList<>();
        for (Sentence s : select(aJCas, Sentence.class)) {
            Annotation sentence = new Annotation(s.getCoveredText());
            sentence.set(CharacterOffsetBeginAnnotation.class, s.getBegin());
            sentence.set(CharacterOffsetEndAnnotation.class, s.getEnd());
            sentence.set(SentenceIndexAnnotation.class, sentences.size());
            
            // Tokens
            List<CoreLabel> tokens = new ArrayList<>();
            for (Token t : selectCovered(Token.class, s)) {
                CoreLabel token = tokenFactory.makeToken(t.getCoveredText(), t.getBegin(),
                        t.getEnd() - t.getBegin());
                // First add token so that tokens.size() returns a 1-based counting as required
                // by IndexAnnotation
                tokens.add(token);
                token.set(SentenceIndexAnnotation.class, sentences.size());
                token.set(IndexAnnotation.class, tokens.size());
                token.set(TokenKey.class, t);
                
                // POS tags
                if (t.getPos() != null) {
                    token.set(PartOfSpeechAnnotation.class, t.getPos().getPosValue());
                }
                
                // Lemma
                if (t.getLemma() != null) {
                    token.set(LemmaAnnotation.class, t.getLemma().getValue());
                }
                
                // Stem
                if (t.getStem() != null) {
                    token.set(StemAnnotation.class, t.getStem().getValue());
                }
                
                // NamedEntity
                // TODO: only token-based NEs are supported, but not multi-token NEs
                // Supporting multi-token NEs via selectCovering would be very slow. To support
                // them, another approach would need to be implemented, e.g. via indexCovering.
                List<NamedEntity> nes = selectCovered(NamedEntity.class, t);
                if (nes.size() > 0) {
                    token.set(NamedEntityTagAnnotation.class, nes.get(0).getValue());
                }
                else {
                    token.set(NamedEntityTagAnnotation.class, "O");
                }
            }

            // Constituents
            for (ROOT r : selectCovered(ROOT.class, s)) {
                Tree tree = createStanfordTree(r);
                tree.indexSpans();
                sentence.set(TreeAnnotation.class, tree);
            }
            
            if (ptb3Escaping) {
                tokens = applyPtbEscaping(tokens, quoteBegin, quoteEnd);
            }

            sentence.set(TokensAnnotation.class, tokens);
            sentences.add(sentence);
        }
        document.set(SentencesAnnotation.class, sentences);
        
        return document;
    }

    /**
     * Recursively creates an edu.stanford.nlp.trees.Tree from a ROOT annotation It also saves the
     * whitespaces before and after a token as <code>CoreAnnotation.BeforeAnnotation</code> and
     * <code>CoreAnnotation.AfterAnnotation</code> in the respective label of the current node.
     * 
     * @param root
     *            the ROOT annotation
     * @return an {@link Tree} object representing the syntax structure of the sentence
     */
    public static Tree createStanfordTree(ROOT root)
    {
        return createStanfordTree(root, new LabeledScoredTreeFactory(CoreLabel.factory()));
    }

    public static Tree createStanfordTree(org.apache.uima.jcas.tcas.Annotation root,
            TreeFactory tFact)
    {
        JCas aJCas;
        try {
            aJCas = root.getCAS().getJCas();
        }
        catch (CASException e) {
            throw new IllegalStateException("Unable to get JCas from JCas wrapper");
        }

        // define the new (root) node
        Tree rootNode;

        // before we can create a node, we must check if we have any children (we have to know
        // whether to create a node or a leaf - not very dynamic)
        if (root instanceof Constituent && !isLeaf((Constituent) root)) {
            Constituent node = (Constituent) root;
            List<Tree> childNodes = new ArrayList<Tree>();

            // get childNodes from child annotations
            FSArray children = node.getChildren();
            for (int i = 0; i < children.size(); i++) {
                childNodes.add(createStanfordTree(node.getChildren(i), tFact));
            }

            // now create the node with its children
            rootNode = tFact.newTreeNode(node.getConstituentType(), childNodes);

        }
        else {
            // Handle leaf annotations
            // Leafs are always Token-annotations
            // We also have to insert a Preterminal node with the value of the
            // POS-Annotation on the token
            // because the POS is not directly stored within the treee
            Token wordAnnotation = (Token) root;

            // create leaf-node for the tree
            Tree wordNode = tFact.newLeaf(wordAnnotation.getCoveredText());

            // create information about preceding and trailing whitespaces in the leaf node
            StringBuilder preWhitespaces = new StringBuilder();
            StringBuilder trailWhitespaces = new StringBuilder();

            List<Token> precedingTokenList = selectPreceding(aJCas, Token.class, wordAnnotation, 1);
            List<Token> followingTokenList = selectFollowing(aJCas, Token.class, wordAnnotation, 1);

            if (precedingTokenList.size() > 0) {
                Token precedingToken = precedingTokenList.get(0);
                int precedingWhitespaces = wordAnnotation.getBegin() - precedingToken.getEnd();
                for (int i = 0; i < precedingWhitespaces; i++) {
                    preWhitespaces.append(" ");
                }
            }
            if (followingTokenList.size() > 0) {
                Token followingToken = followingTokenList.get(0);
                int trailingWhitespaces = followingToken.getBegin() - wordAnnotation.getEnd();
                for (int i = 0; i < trailingWhitespaces; i++) {
                    trailWhitespaces.append(" ");
                }
            }

            // write whitespace information as CoreAnnotation.BeforeAnnotation and
            // CoreAnnotation.AfterAnnotation to the node add annotation to list and write back to
            // node label
            ((CoreLabel) wordNode.label()).set(CoreAnnotations.BeforeAnnotation.class,
                    preWhitespaces.toString());
            ((CoreLabel) wordNode.label()).set(CoreAnnotations.AfterAnnotation.class,
                    trailWhitespaces.toString());

            // get POS-annotation
            POS pos = wordAnnotation.getPos();

            // create POS-Node in the tree and attach word-node to it
            rootNode = tFact.newTreeNode(pos.getPosValue(),
                    Arrays.asList((new Tree[] { wordNode })));
        }

        return rootNode;
    }
    
    private static boolean isLeaf(Constituent constituent)
    {
        return (constituent.getChildren() == null || constituent.getChildren().size() == 0);
    }
    
    @SuppressWarnings("unchecked")
    public static <T extends HasWord> List<T> applyPtbEscaping(List<T> words,
            Collection<String> quoteBegin, Collection<String> quoteEnd)
    {
        PTBEscapingProcessor<T, String, Word> escaper = new PTBEscapingProcessor<T, String, Word>();
        // Apply escaper to the whole sentence, not to each token individually. The
        // escaper takes context into account, e.g. when transforming regular double
        // quotes into PTB opening and closing quotes (`` and '').
        words = (List<T>) escaper.apply(words);
        
        for (HasWord w : words) {
            if (quoteBegin != null && quoteBegin.contains(w.word())) {
                w.setWord("``");
            }
            else if (quoteEnd != null && quoteEnd.contains(w.word())) {
                w.setWord("\'\'");
            }
        }
        
        return words;
    }
}
