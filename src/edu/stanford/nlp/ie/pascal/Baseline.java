package edu.stanford.nlp.ie.pascal;

import edu.stanford.nlp.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Initial attempt at a top-down model to classify dates by fitting
 * guassians to the distance between them.
 * Not used in the final Pascal system.
 *
 * @author Jenny Finkel
 */

public class Baseline {

  public static final String INVALID_DATE = "1/1/1000";

  private static boolean USE_DAY_OF_WEEK = false;
  private static boolean USE_MONTH = true;
  private static boolean FORCE_ORDERING = false;
  private static boolean FORCE_STRICT_ORDERING = false;
  private static boolean USE_NUM_OCCURRENCES = false;
  private static boolean USE_RANGE = true;


  private Baseline() {
  }


  public static class DateRangePair {

    public Calendar date;
    public boolean range;
    public boolean isNull = false;
    public int numOccurrences = 1;

    private String dateString;

    public DateRangePair(String date, boolean range) {
      this.date = Calendar.getInstance();
      this.date.clear();
      String[] d = date.split("/");
      this.date.set(Integer.parseInt(d[2]), Integer.parseInt(d[0]) - 1, Integer.parseInt(d[1]));
      this.range = range;
      this.dateString = date;
    }

    public DateRangePair() {
      date = null;
      range = false;
      isNull = true;
      dateString = String.valueOf(Calendar.getInstance().getTimeInMillis());
    }

    private int hash = -1;

    @Override
    public int hashCode() {
      if (hash < 0) {
        hash = dateString.hashCode();
      }
      return hash;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof DateRangePair)) {
        return false;
      }
      if (isNull) {
        return false;
      }
      DateRangePair other = (DateRangePair) o;
      if (range != other.range) {
        return false;
      }
      return dateString.equals(other.dateString);
    }

    @Override
    public String toString() {
      if (isNull) {
        return INVALID_DATE;
      } else {
        return dateString;
      }
    }
  }

  public static Set<DateRangePair> getDates(File infile) {
    try {
      HashSet<DateRangePair> dates = new HashSet<DateRangePair>();
      String file = IOUtils.slurpFile(infile);
      String p = "<DATE.*?>";
      Pattern pattern = Pattern.compile(p, Pattern.CASE_INSENSITIVE);
      Matcher matcher = pattern.matcher(file);
      Pattern datePattern = Pattern.compile("normalized=\"([0-9/]*)\"");
      Pattern rangePattern = Pattern.compile("range=\"((?:true)|(?:false))\"");
      while (matcher.find()) {
        String d = matcher.group();
        Matcher dateMatcher = datePattern.matcher(d);
        dateMatcher.find();
        String date = dateMatcher.group(1);
        if (date.equals(INVALID_DATE)) {
          continue;
        }
        Matcher rangeMatcher = rangePattern.matcher(d);
        rangeMatcher.find();
        boolean range = Boolean.parseBoolean(rangeMatcher.group(1));
        DateRangePair drp = new DateRangePair(date, range);
        if (dates.contains(drp)) {
          dates.remove(drp);
          drp.numOccurrences++;
        }
        dates.add(drp);
      }
      while (dates.size() < 4) {
        dates.add(new DateRangePair());
      }
      return dates;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static List<DateRangePair> getBest(Set<DateRangePair> dates) {
    List<DateRangePair> bestList = null;
    double bestProb = Double.NEGATIVE_INFINITY;

    for (DateRangePair workshopDate : dates) {
      for (DateRangePair cameraReadyDate : dates) {
        if (cameraReadyDate == workshopDate) {
          continue;
        }

        for (DateRangePair notificationDate : dates) {
          if (notificationDate == workshopDate || notificationDate == cameraReadyDate) {
            continue;
          }

          for (DateRangePair paperSubmitDate : dates) {
            if (paperSubmitDate == workshopDate || paperSubmitDate == cameraReadyDate || paperSubmitDate == notificationDate) {
              continue;
            }

            double prob = prob(workshopDate, cameraReadyDate, notificationDate, paperSubmitDate);

            if (prob >= bestProb) {
              bestProb = prob;
              bestList = new ArrayList<DateRangePair>();
              bestList.add(paperSubmitDate);
              bestList.add(notificationDate);
              bestList.add(cameraReadyDate);
              bestList.add(workshopDate);
            }
          }
        }
      }
    }
    return bestList;
  }

  public static double prob(DateRangePair workshopDate, DateRangePair cameraReadyDate, DateRangePair notificationDate, DateRangePair paperSubmitDate) {

    if (FORCE_ORDERING || FORCE_STRICT_ORDERING) {
      if (delta(paperSubmitDate, workshopDate) < 0.0) {
        return Double.NEGATIVE_INFINITY;
      }
      if (delta(notificationDate, workshopDate) < 0.0) {
        return Double.NEGATIVE_INFINITY;
      }
      if (FORCE_STRICT_ORDERING) {
        if (delta(cameraReadyDate, workshopDate) < 0.0) {
          return Double.NEGATIVE_INFINITY;
        }
      }
      if (delta(paperSubmitDate, cameraReadyDate) < 0.0) {
        return Double.NEGATIVE_INFINITY;
      }
      if (delta(notificationDate, cameraReadyDate) < 0.0) {
        return Double.NEGATIVE_INFINITY;
      }
      if (delta(paperSubmitDate, notificationDate) < 0.0) {
        return Double.NEGATIVE_INFINITY;
      }
    }

    double prob;

    if (workshopDate.isNull) {
      prob = nulls[WS][NULL];
    } else {
      prob = nulls[WS][NOT_NULL];
      if (USE_MONTH) {
        prob += months[WS][workshopDate.date.get(Calendar.MONTH)];
      }
    }

    if (cameraReadyDate.isNull) {
      prob += nulls[CR][NULL];
    } else {
      prob += nulls[CR][NOT_NULL];

      if (!workshopDate.isNull) {
        prob += gaussian(delta(cameraReadyDate, workshopDate), means[CR][WS], sds[CR][WS]);
      } else {
        if (USE_MONTH) {
          prob += months[CR][cameraReadyDate.date.get(Calendar.MONTH)];
        }
      }
    }

    if (notificationDate.isNull) {
      prob += nulls[NOA][NULL];
    } else {
      prob += nulls[NOA][NOT_NULL];

      if (!cameraReadyDate.isNull) {
        prob += gaussian(delta(notificationDate, cameraReadyDate), means[NOA][CR], sds[NOA][CR]);
      } else if (!workshopDate.isNull) {
        prob += gaussian(delta(notificationDate, workshopDate), means[NOA][WS], sds[NOA][WS]);
      } else {
        if (USE_MONTH) {
          prob += months[NOA][notificationDate.date.get(Calendar.MONTH)];
        }
      }
    }

    if (paperSubmitDate.isNull) {
      prob += nulls[PS][NULL];
    } else {
      prob += nulls[PS][NOT_NULL];

      if (!notificationDate.isNull) {
        prob += gaussian(delta(paperSubmitDate, notificationDate), means[PS][NOA], sds[PS][NOA]);
      } else if (!cameraReadyDate.isNull) {
        prob += gaussian(delta(paperSubmitDate, cameraReadyDate), means[PS][CR], sds[PS][CR]);
      } else if (!workshopDate.isNull) {
        prob += gaussian(delta(paperSubmitDate, workshopDate), means[PS][WS], sds[PS][WS]);
      } else {
        if (USE_MONTH) {
          prob += months[PS][paperSubmitDate.date.get(Calendar.MONTH)];
        }
      }
    }

    if (USE_NUM_OCCURRENCES) {
      prob /= workshopDate.numOccurrences;
      prob /= cameraReadyDate.numOccurrences;
      prob /= notificationDate.numOccurrences;
      prob /= paperSubmitDate.numOccurrences;
    }

    if (USE_DAY_OF_WEEK) {
      if (!workshopDate.isNull) {
        prob += dayOfWeeks[WS][workshopDate.date.get(Calendar.DAY_OF_WEEK) - 1];
      }
      if (!cameraReadyDate.isNull) {
        prob += dayOfWeeks[CR][cameraReadyDate.date.get(Calendar.DAY_OF_WEEK) - 1];
      }
      if (!notificationDate.isNull) {
        prob += dayOfWeeks[NOA][notificationDate.date.get(Calendar.DAY_OF_WEEK) - 1];
      }
      if (!paperSubmitDate.isNull) {
        prob += dayOfWeeks[PS][paperSubmitDate.date.get(Calendar.DAY_OF_WEEK) - 1];
      }
    }

    if (USE_RANGE) {
      if (workshopDate.range) {
        prob += ranges[WS][RANGE];
      } else {
        prob += ranges[WS][NOT_RANGE];
      }

      if (cameraReadyDate.range) {
        prob += ranges[CR][RANGE];
      } else {
        prob += ranges[CR][NOT_RANGE];
      }

      if (notificationDate.range) {
        prob += ranges[NOA][RANGE];
      } else {
        prob += ranges[NOA][NOT_RANGE];
      }

      if (paperSubmitDate.range) {
        prob += ranges[PS][RANGE];
      } else {
        prob += ranges[PS][NOT_RANGE];
      }
    }

    return prob;
  }

  public static double gaussian(double x, double mean, double sd) {
    double a = -Math.pow((x - mean), 2) / (2 * sd);
    double den = Math.sqrt(2 * Math.PI * sd);
    double p = a - Math.log(den);
    return p;
  }

  private static final int millisInDay = 1000 * 60 * 60 * 24;

  public static double delta(DateRangePair date1, DateRangePair date2) {
    if (date1.isNull || date2.isNull) {
      return Double.POSITIVE_INFINITY;
    }

    long millis1 = date1.date.getTimeInMillis();
    long millis2 = date2.date.getTimeInMillis();
    return (millis2 - millis1) / (double) millisInDay;
  }

  private static final int PS = 0;
  private static final int NOA = 1;
  private static final int CR = 2;
  private static final int WS = 3;

  private static String[] dateNames = {"sub", "noa", "crc", "ws"};

  private static final int NOT_NULL = 0;
  private static final int NULL = 1;

  private static double[][] means = new double[4][4];
  private static double[][] sds = new double[4][4];
  private static double[][] nulls = new double[4][2];
  private static double[][] dayOfWeeks = new double[4][7];
  private static double[][] months = new double[4][12];

  private static double[][] ranges = new double[4][2];

  private static final int NOT_RANGE = 0;
  private static final int RANGE = 1;


  public static void readParams(String filename) {
    Properties prop = new Properties();
    FileInputStream fis = null;
    try {
      fis = new FileInputStream(filename);
      prop.load(fis);
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      if (fis != null) {
        try {
          fis.close();
        } catch (IOException ioe) {
          // ignore
        }
      }
    }

    String m = prop.getProperty("gaussian_means");
    String s = prop.getProperty("gaussian_deviations");
    String[] ms = m.split(",");
    String[] ss = s.split(",");
    means[PS][NOA] = means[NOA][PS] = Double.parseDouble(ms[0]);
    sds[PS][NOA] = sds[NOA][PS] = Double.parseDouble(ss[0]);
    means[PS][CR] = means[CR][PS] = Double.parseDouble(ms[1]);
    sds[PS][CR] = sds[CR][PS] = Double.parseDouble(ss[1]);
    means[PS][WS] = means[WS][PS] = Double.parseDouble(ms[2]);
    sds[PS][WS] = sds[WS][PS] = Double.parseDouble(ss[2]);
    means[NOA][CR] = means[CR][NOA] = Double.parseDouble(ms[3]);
    sds[NOA][CR] = sds[CR][NOA] = Double.parseDouble(ss[3]);
    means[NOA][WS] = means[WS][NOA] = Double.parseDouble(ms[4]);
    sds[NOA][WS] = sds[WS][NOA] = Double.parseDouble(ss[4]);
    means[CR][WS] = means[WS][CR] = Double.parseDouble(ms[5]);
    sds[CR][WS] = sds[WS][CR] = Double.parseDouble(ss[5]);

    for (int i = 0; i < dateNames.length; i++) {
      String key = "null_distribution_" + dateNames[i];
      String n = prop.getProperty(key);
      String[] ns = n.split(",");
      for (int j = 0; j < ns.length; j++) {
        nulls[i][j] = Math.pow(Math.E, Double.parseDouble(ns[j]));
      }
    }

    for (int i = 0; i < dateNames.length; i++) {
      String key = "day_of_week_distribution_" + dateNames[i];
      String d = prop.getProperty(key);
      String[] ds = d.split(",");
      for (int j = 0; j < ds.length; j++) {
        dayOfWeeks[i][j] = Math.pow(Math.E, Double.parseDouble(ds[j]));
      }
    }

    for (int i = 0; i < dateNames.length; i++) {
      String key = "month_distribution_" + dateNames[i];
      m = prop.getProperty(key);
      ms = m.split(",");
      for (int j = 0; j < ms.length; j++) {
        months[i][j] = Math.pow(Math.E, Double.parseDouble(ms[j]));
      }
    }

    for (int i = 0; i < dateNames.length; i++) {
      String key = "range_distribution_" + dateNames[i];
      String r = prop.getProperty(key);
      String[] rs = r.split(",");
      for (int j = 0; j < rs.length; j++) {
        ranges[i][j] = Math.pow(Math.E, Double.parseDouble(rs[j]));
      }
    }

  }

  public static void main(String[] args) {

    String paramFile = null;
    String dirName = null;
    String ffPattern = null;

    for (int i = 0; i < args.length; i++) {
      if (args[i].equalsIgnoreCase("use_day_of_week")) {
        USE_DAY_OF_WEEK = true;
      } else if (args[i].equalsIgnoreCase("no_range")) {
        USE_RANGE = false;
      } else if (args[i].equalsIgnoreCase("no_month")) {
        USE_MONTH = false;
      } else if (args[i].equalsIgnoreCase("-p")) {
        paramFile = args[++i];
      } else if (args[i].equalsIgnoreCase("-d")) {
        dirName = args[++i];
      } else if (args[i].equalsIgnoreCase("-ff")) {
        ffPattern = args[++i];
      } else if (args[i].equalsIgnoreCase("-fo")) {
        FORCE_ORDERING = true;
      } else if (args[i].equalsIgnoreCase("-fso")) {
        FORCE_ORDERING = FORCE_STRICT_ORDERING = true;
      } else if (args[i].equalsIgnoreCase("use_num_occurrences")) {
        USE_NUM_OCCURRENCES = true;
      }
    }

    readParams(paramFile);

    File dir = new File(dirName);
    Pattern pattern = Pattern.compile(ffPattern);
    FilenameFilter ff = new DateFileFilter(pattern);
    File[] files = dir.listFiles(ff);
    for (File f : files) {
      System.out.print(f.getName() + "\t");
      Set<DateRangePair> allDates = getDates(f);
      List<DateRangePair> best = getBest(allDates);
      System.out.print(best.get(0) + "\t");
      System.out.print(best.get(1) + "\t");
      System.out.print(best.get(2) + "\t");
      System.out.println(best.get(3) + "\t");
    }
  }

  private static class DateFileFilter implements FilenameFilter {
    Pattern pattern;

    public DateFileFilter(Pattern p) {
      pattern = p;
    }

    public boolean accept(File dir, String name) {
      return pattern.matcher(name).matches();
    }
  }

}
