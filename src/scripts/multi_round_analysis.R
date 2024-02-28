# Load required libraries using pacman
pacman::p_load(data.table)

# Load the path to the csv files
args <- commandArgs(trailingOnly = TRUE)
csv_directory_path <- args[1]

# List all CSV files in the directory
files <- list.files(path = csv_directory_path, pattern = "^Network.*\\.csv$")
files <- paste0(csv_directory_path, "/", files)

# Read all files into one data.table
dt_list <- lapply(files, function(file) {
  # Read the CSV file into a data table
  dt <- fread(file)

  # Extract the name of the simulation from the file path
  # This assumes the file name is the simulation name followed by ".csv"
  simulation_name <- tools::file_path_sans_ext(basename(file))

  # Add a new column with the simulation name
  dt[, SimulationName := simulation_name]

  # Return the data table
  return(dt)
})

# Combine all data tables into one, keeping the SimulationName column
all_data <- rbindlist(dt_list)


# Compute summary statistics for each simulation
simulation_stats <- all_data[, .(
  meanBelief = mean(belief, na.rm = TRUE),
  sdBelief = sd(belief, na.rm = TRUE),
  meanConfidence = mean(confidence, na.rm = TRUE),
  sdConfidence = sd(confidence, na.rm = TRUE),
  maxRound = max(round, na.rm = TRUE),  # Get the maximum round number for each simulation
  numRounds = uniqueN(round)  # Count the distinct number of rounds per simulation
), by = SimulationName]


# Get last round for each network
all_data[, maxRound := simulation_stats$maxRound[match(SimulationName, simulation_stats$SimulationName)]]
final_round_data <- all_data[round == maxRound, .SD, by = .(SimulationName, round)]
last_rounds <- final_round_data[, .(sumation = sum(confidence)), by = SimulationName]

# Determine the "average" simulation based on median summary statistics
median_stats <- simulation_stats[, .(
  medianMeanBelief = median(meanBelief),
  medianSdBelief = median(sdBelief),
  medianMeanConfidence = median(meanConfidence),
  medianSdConfidence = median(sdConfidence),
  medianMaxRound = median(maxRound),
  medianNumRounds = median(numRounds)
)]

# Calculate the Euclidean distance from the median for each simulation
simulation_stats[, distance := sqrt(
  (meanBelief - median_stats$medianMeanBelief)^2 +
    (sdBelief - median_stats$medianSdBelief)^2 +
    (meanConfidence - median_stats$medianMeanConfidence)^2 +
    (sdConfidence - median_stats$medianSdConfidence)^2 +
    (numRounds - median_stats$medianNumRounds)^2
)]

# The simulation with the smallest distance to the median is the "average" simulation
average_simulation <- simulation_stats[which.min(distance), .(
  SimulationName, meanBelief, sdBelief, meanConfidence, sdConfidence, maxRound, numRounds
)]

# Outlier detection using the IQR method for each statistic
iqr_stats <- simulation_stats[, .(
  iqrMeanBelief = IQR(meanBelief),
  iqrSdBelief = IQR(sdBelief),
  iqrMeanConfidence = IQR(meanConfidence),
  iqrSdConfidence = IQR(sdConfidence),
  iqrMaxRound = IQR(maxRound),
  iqrNumRounds = IQR(numRounds)
)]

# Identify outliers as those simulations that fall outside 1.5 * IQR of the median
outliers <- simulation_stats[
  meanBelief < (median_stats$medianMeanBelief - 1.5 * iqr_stats$iqrMeanBelief) |
    meanBelief > (median_stats$medianMeanBelief + 1.5 * iqr_stats$iqrMeanBelief) |
    meanConfidence < (median_stats$medianMeanConfidence - 1.5 * iqr_stats$iqrMeanConfidence) |
    meanConfidence > (median_stats$medianMeanConfidence + 1.5 * iqr_stats$iqrMeanConfidence) |
    numRounds < (median_stats$medianNumRounds - 1.5 * iqr_stats$iqrNumRounds) |
    numRounds > (median_stats$medianNumRounds + 1.5 * iqr_stats$iqrNumRounds),
  .(SimulationName, meanBelief, sdBelief, meanConfidence, sdConfidence, maxRound, numRounds)
]

# Export the "average" simulation and outliers
save_directory <- paste0(csv_directory_path, "/r_outputs")
dir.create(save_directory)
fwrite(average_simulation, paste0(save_directory, "/", "average_simulation.csv"))
fwrite(outliers, paste0(save_directory, "/", "outliers.csv"))
print("Guardado con exito")
