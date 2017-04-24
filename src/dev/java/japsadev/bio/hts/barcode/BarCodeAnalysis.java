package japsadev.bio.hts.barcode;
import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;

import jaligner.matrix.MatrixLoaderException;
import japsa.seq.Alphabet;
import japsa.seq.Sequence;
import japsa.seq.SequenceOutputStream;
import japsa.seq.SequenceReader;
import japsa.util.Logging;

public class BarCodeAnalysis {
	int 	SCAN_WINDOW, 
			DIST_THRES,
			SCORE_THRES;
	public static boolean 	print=false,
							strict=false; // both-ends-matching 
	ArrayList<Sequence> barCodesLeft = new ArrayList<Sequence>(); //barcode sequences from left end
	ArrayList<Sequence> barCodesRight = new ArrayList<Sequence>(); //barcode from right end
	Process[] processes;
	int nSamples;
	int barcodeLen;
	SequenceOutputStream[] streamToScaffolder, streamToFile;

	public BarCodeAnalysis(String barcodeFile, String scriptFile) throws IOException{
		ArrayList<Sequence> allSeq = SequenceReader.readAll(barcodeFile, Alphabet.DNA());
		if(strict){
			allSeq.sort(Comparator.comparing(Sequence::getName));
			for(int i=0;i<allSeq.size();i++){
				barCodesLeft.add(allSeq.get(i++));
				barCodesRight.add(allSeq.get(i));
			}
		}else{
			barCodesLeft = allSeq;
			for(Sequence seq:barCodesLeft)
				barCodesRight.add(Alphabet.DNA.complement(seq));

		}
		
		nSamples = barCodesLeft.size();

		processes = new Process[nSamples];
		streamToScaffolder = new SequenceOutputStream[nSamples];
		if(print)
			streamToFile = new SequenceOutputStream[nSamples+1]; //unknown sequences included

		String id;
		for(int i=0;i<nSamples;i++){		
			Sequence barCode = barCodesLeft.get(i);

			id = barCode.getName();
			//System.out.println(i + " >" + id + ":" + barCode);

			ProcessBuilder pb = new ProcessBuilder(scriptFile, id)
					.redirectError(new File("log_" + id + ".err"))
					.redirectOutput(new File("log_" + id + ".out"));
			pb.directory(new File(System.getProperty("user.dir")));
			
			processes[i]  = pb.start();

			Logging.info("Job for " + id  + " started");
			streamToScaffolder[i] = new SequenceOutputStream(processes[i].getOutputStream());
		}
		
		if(print){
			streamToFile = new SequenceOutputStream[nSamples+1]; // plus unknown
			for(int i=0;i<nSamples;i++){		
				streamToFile[i] = SequenceOutputStream.makeOutputStream(barCodesLeft.get(i).getName()+"_clustered.fastq");
			}
			streamToFile[nSamples] = SequenceOutputStream.makeOutputStream("unknown_clustered.fastq");

		}
		
		barcodeLen = barCodesLeft.get(0).length();
		SCAN_WINDOW = barcodeLen * 3;
		SCORE_THRES = barcodeLen;
		DIST_THRES = strict?SCORE_THRES/2:SCORE_THRES/3;
	}
	
	public void setThreshold(int score){
		SCORE_THRES=score;
		DIST_THRES = strict?SCORE_THRES/2:SCORE_THRES/3;
	}
	/*
	 * Trying to clustering MinION read data into different samples based on the barcode
	 */
	public void clustering(String dataFile) throws IOException, InterruptedException, MatrixLoaderException{
		SequenceReader reader;
		if(dataFile.equals("-"))
			reader = SequenceReader.getReader(System.in);
		else
			reader = SequenceReader.getReader(dataFile);
		Sequence seq;

		Sequence s5, s3;
		final double[] 	lf = new double[nSamples], //left-forward
				lr = new double[nSamples],	//left-reversed
				rr = new double[nSamples], //right-reversed
				rf = new double[nSamples]; //right-forward
		
		jaligner.Alignment 	alignmentLF = new jaligner.Alignment(),
							alignmentLR = new jaligner.Alignment(),
							alignmentRF = new jaligner.Alignment(),
							alignmentRR = new jaligner.Alignment();
		jaligner.Sequence js5,js3, jBarcodeLeft, jBarcodeRight;
		jaligner.Alignment 	bestLeftAlignment = new jaligner.Alignment(),
							bestRightAlignment = new jaligner.Alignment();

//		Sequence barcodeSeq = new Sequence(Alphabet.DNA4(),barcodeLen,"barcode");
//		Sequence tipSeq = new Sequence(Alphabet.DNA4(),SCAN_WINDOW,"tip");
//
//		BarcodeAlignment barcodeAlignment = new BarcodeAlignment(barcodeSeq, tipSeq);

		while ((seq = reader.nextSequence(Alphabet.DNA())) != null){
			if(seq.length() < 200){
				System.err.println("Ignore short sequence " + seq.getName());
				continue;
			}
			//alignment algorithm is applied here. For the beginning, Smith-Waterman local pairwise alignment is used

			s5 = seq.subSequence(0, SCAN_WINDOW);
			s3 = Alphabet.DNA.complement(seq.subSequence(seq.length()-SCAN_WINDOW,seq.length()));



			double bestScore = 0.0;
			double distance = 0.0; //distance between bestscore and the runner-up
			
			int bestIndex = nSamples;

			for(int i=0;i<nSamples; i++){
				Sequence barcodeLeft = barCodesLeft.get(i);
				Sequence barcodeRight = barCodesRight.get(i); //rc of right barcode sequence

//				barcodeAlignment.setBarcodeSequence(barcodeLeft);				
//				barcodeAlignment.setReadSequence(s5);				
//				lf[i]=barcodeAlignment.align();				
//
//				barcodeAlignment.setReadSequence(Alphabet.DNA.complement(s3));
//				lr[i]=barcodeAlignment.align();
//
//				barcodeAlignment.setBarcodeSequence(barcodeRight);
//				barcodeAlignment.setReadSequence(Alphabet.DNA.complement(s3));
//				rr[i]=barcodeAlignment.align();
//				
//				barcodeAlignment.setReadSequence(s5);
//				rf[i]=barcodeAlignment.align();
				js5 = new jaligner.Sequence(s5.toString());
				js3 = new jaligner.Sequence(s3.toString());
				jBarcodeLeft = new jaligner.Sequence(barcodeLeft.toString());
				jBarcodeRight = new jaligner.Sequence(barcodeRight.toString());

				alignmentLF = jaligner.SmithWatermanGotoh.align(js5, jBarcodeLeft, jaligner.matrix.MatrixLoader.load("BLOSUM62"), 10f, 0.5f);
				alignmentLR = jaligner.SmithWatermanGotoh.align(js3, jBarcodeLeft, jaligner.matrix.MatrixLoader.load("BLOSUM62"), 10f, 0.5f);
				alignmentRF = jaligner.SmithWatermanGotoh.align(js5, jBarcodeRight, jaligner.matrix.MatrixLoader.load("BLOSUM62"), 10f, 0.5f);
				alignmentRR = jaligner.SmithWatermanGotoh.align(js3, jBarcodeRight, jaligner.matrix.MatrixLoader.load("BLOSUM62"), 10f, 0.5f);
				
				lf[i] = alignmentLF.getScore();
				lr[i] = alignmentLR.getScore();
				rf[i] = alignmentRF.getScore();
				rr[i] = alignmentRR.getScore();
				

				double myScore = 0.0;
				if(strict){
					myScore = Math.max(lf[i] + rr[i] , lr[i] + rf[i]);
				}
				else{	
					myScore = Math.max(Math.max(lf[i], lr[i]), Math.max(rf[i], rr[i]));
				}
				
				if (myScore > bestScore){
					//Logging.info("Better score=" + myScore);
					distance = myScore-bestScore;
					bestScore = myScore;		
					bestIndex = i;
					if(strict){
						if(lf[i] + rr[i] > lr[i] + rf[i]){
							bestLeftAlignment = alignmentLF;
							bestRightAlignment = alignmentRR;
						}else{
							bestLeftAlignment = alignmentLR;
							bestRightAlignment = alignmentRF;
						}
						
					}else{
						if(myScore==lf[i] || myScore==rr[i]){
							bestLeftAlignment = alignmentLF;
							bestRightAlignment = alignmentRR;
						}else{
							bestLeftAlignment = alignmentLR;
							bestRightAlignment = alignmentRF;
						}
					}
					
				} else if((bestScore-myScore) < distance){
					distance=bestScore-myScore;
				}
					
			}
			
			
			String retval="";
			DecimalFormat twoDForm =  new DecimalFormat("#.##");
			if(bestScore < SCORE_THRES || distance < DIST_THRES){
				//Logging.info("Unknown sequence " + seq.getName());
				retval = "unknown:"+Double.valueOf(twoDForm.format(bestScore))+":"+Double.valueOf(twoDForm.format(distance))+"|0-0:0-0|";
				seq.setName(retval + seq.getName());

				if(print)
					seq.print(streamToFile[nSamples]);
			}
			//if the best (sum of both ends) alignment in template sequence is greater than in complement
			else {
//				Logging.info("Sequence " + seq.getName() + " might belongs to sample " + barCodesLeft.get(bestIndex).getName() + " with score=" + bestScore);
				if(bestIndex<nSamples && processes[bestIndex]!=null && processes[bestIndex].isAlive()){
					retval = barCodesLeft.get(bestIndex).getName()+":"+Double.valueOf(twoDForm.format(bestScore))+":"+Double.valueOf(twoDForm.format(distance))+"|";
					int s1 = bestLeftAlignment.getStart1(),
						e1 = bestLeftAlignment.getStart1()+bestLeftAlignment.getSequence1().length-bestLeftAlignment.getGaps1(),
						e2 = seq.length()-1-bestRightAlignment.getStart1(),
						s2 = seq.length()-1-(bestRightAlignment.getStart1()+bestRightAlignment.getSequence1().length-bestRightAlignment.getGaps1());
					retval += s1+"-"+e1+":"+s2+"-"+e2+"|";
					seq.setName(retval + seq.getName());

//					Logging.info("...writing to stream " + bestIndex);
					System.out.println(seq.getName());
					printAlignment(bestLeftAlignment);
					System.out.println();
					printAlignment(bestRightAlignment);
					System.out.println("==================================================================================");
					
					seq.print(streamToScaffolder[bestIndex]);
					if(print)
						seq.print(streamToFile[bestIndex]);
				}
			}

		}

		System.out.println("Done all input");
		for (int i = 0; i < nSamples;i++){
			if(processes[i]!=null && processes[i].isAlive()){
				streamToScaffolder[i].close();
				if(print)
					streamToFile[i].close();
				processes[i].waitFor();
			}
		}
		if(print)
			streamToFile[nSamples].close();
		System.out.println("Done every thing");
		reader.close();
	}

		//display jaligner.Alignment. TODO: convert to ours
		public void printAlignment(jaligner.Alignment alignment){
			String 	origSeq1 = alignment.getOriginalSequence1().getSequence(),
					origSeq2 = alignment.getOriginalSequence2().getSequence(),
					alnSeq1 = new String(alignment.getSequence1()),
					alnSeq2 = new String(alignment.getSequence2());
			int 	start1 = alignment.getStart1(),
					start2 = alignment.getStart2(),
					gap1 = alignment.getGaps1(),
					gap2 = alignment.getGaps2();
			
			String seq1, seq2, mark;
			if(start1>=start2){
				seq1=origSeq1.substring(0, start1) + alnSeq1 + origSeq1.substring(start1+alnSeq1.length()-gap1);
				String 	seq2Filler = start1==start2?"":String.format("%"+(start1-start2)+"s", ""),
						markFiller = start1==0?"":String.format("%"+start1+"s", "");
				seq2= seq2Filler + origSeq2.substring(0, start2) + alnSeq2 + origSeq2.substring(start2+alnSeq2.length()-gap2);
				mark= markFiller+String.valueOf(alignment.getMarkupLine());
			}else{
				seq2=origSeq2.substring(0, start2) + alnSeq2 + origSeq2.substring(start2+alnSeq2.length()-gap2);
				String 	markFiller = start2==0?"":String.format("%"+start2+"s", "");
				seq1=String.format("%"+(start2-start1)+"s", "") + origSeq1.substring(0, start1) + alnSeq1 + origSeq1.substring(start1+alnSeq1.length()-gap1);
				mark=markFiller+String.valueOf(alignment.getMarkupLine());
			}
			System.out.println(alignment.getSummary());
			System.out.println(seq1);
			System.out.println(mark);
			System.out.println(seq2);
		}

}
