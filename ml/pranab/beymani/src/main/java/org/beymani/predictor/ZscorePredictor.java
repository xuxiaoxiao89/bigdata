/*
 * beymani: Outlier and anamoly detection 
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.beymani.predictor;

import java.io.IOException;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.chombo.storm.Cache;
import org.chombo.storm.MessageQueue;
import org.chombo.util.ConfigUtility;
import org.chombo.util.NumericalAttrStatsManager;
import org.chombo.util.Utility;

/**
 * @author pranab
 *
 */
public class ZscorePredictor  extends ModelBasedPredictor{
	private int[] idOrdinals;
	private int[] attrOrdinals;
	private NumericalAttrStatsManager statsManager;
	private String fieldDelim;
	private double[] attrWeights;
	protected MessageQueue outQueue;
	protected Cache cache;
	
	/**
	 * Storm usage
	 * @param config
	 * @param idOrdinalsParam
	 * @param attrListParam
	 * @param fieldDelimParam
	 * @param attrWeightParam
	 * @param statsModelKeyParam
	 * @throws IOException
	 */
	public ZscorePredictor(Map config, String idOrdinalsParam, String attrListParam, String fieldDelimParam, 
			String attrWeightParam, String statsModelKeyParam, String scoreThresholdParam) 
		throws IOException {
		idOrdinals = ConfigUtility.getIntArray(config, idOrdinalsParam);
		attrOrdinals = ConfigUtility.getIntArray(config, attrListParam);
		fieldDelim = ConfigUtility.getString(config, fieldDelimParam, ",");

		outQueue = MessageQueue.createMessageQueue(config, config.get("output.queue").toString());
		cache = Cache.createCache(config);
		
		String modelKey =  config.get(statsModelKeyParam).toString();
		String model = cache.get(modelKey);
		statsManager = new NumericalAttrStatsManager(model, ",");

		attrWeights = ConfigUtility.getDoubleArray(config, attrWeightParam);
		scoreThreshold = ConfigUtility.getDouble(config, scoreThresholdParam, 3.0);
		realTimeDetection = true;
	}

	/**
	 * Hadoop MR usage for z score
	 * @param config
	 * @param idOrdinalsParam
	 * @param asttrListParam
	 * @param statsFilePath
	 * @param schemaFilePath
	 * @param fieldDelimParam
	 * @throws IOException
	 */
	public ZscorePredictor(Configuration config, String idOrdinalsParam, String attrListParam, 
		String statsFilePathParam,  String fieldDelimParam, String attrWeightParam, String scoreThresholdParam) throws IOException {
		idOrdinals = Utility.intArrayFromString(config.get(idOrdinalsParam));
		attrOrdinals = Utility.intArrayFromString(config.get(attrListParam));
		statsManager = new NumericalAttrStatsManager(config, statsFilePathParam, ",");
		fieldDelim = config.get(fieldDelimParam, ",");
		
		//attribute weights
		attrWeights = Utility.doubleArrayFromString(config.get(attrWeightParam), fieldDelim);
		scoreThreshold = Double.parseDouble( config.get( scoreThresholdParam));
	}
	

	@Override
	public double execute(String entityID, String record) {
		double score = 0;
		String[] items = record.split(fieldDelim);
		int i = 0;
		double totalWt = 0;
		for (int ord  :  attrOrdinals) {
			double val = Double.parseDouble(items[ord]);
			if (null != idOrdinals) {
				String compKey = Utility.join(items, idOrdinals, fieldDelim);
				score  += (Math.abs( val -  statsManager.getMean(compKey,ord)) / statsManager.getStdDev(compKey, ord)) * attrWeights[i];
			} else {
				score  += (Math.abs( val -  statsManager.getMean(ord)) / statsManager.getStdDev(ord)) * attrWeights[i];
			}
			totalWt += attrWeights[i];
			++i;
		}
		score /=  totalWt ;

		scoreAboveThreshold = score > scoreThreshold;
		if (realTimeDetection && scoreAboveThreshold) {
			//write if above threshold
			outQueue.send(entityID + " " + score);
		}
		return score;
	}

}
