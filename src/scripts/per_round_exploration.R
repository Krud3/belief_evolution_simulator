# Load required libraries using pacman
pacman::p_load(data.table, ggplot2, magick, ggpubr, gganimate, scales)

# Load the path
args <- commandArgs(trailingOnly = TRUE)
csv_directory_path <- args[1]
networkName <- args[2]
#csv_directory_path <- "src/data/runs/run_2024-02-23_17-48-51_173"
#networkName <- "Network1_density3"
file_path <- paste0(csv_directory_path, "/", networkName, ".csv")
static_file_path <- paste0(csv_directory_path, "/", "static_", networkName, ".csv")

save_directory <- paste0(csv_directory_path, "/graphs")
if (!dir.exists(save_directory)) {
  dir.create(save_directory)
}

save_directory <- paste0(save_directory, "/", networkName)
dir.create(save_directory)

# Load the data into a data.table
dt <- fread(file_path)
dt_static <- fread(static_file_path)

# Check the most popular agent
agent_name <- dt_static[max(numberOfNeighbors), ]$agentName

# Filter the data for a single agent, e.g., "Agent992"
singleAgentDT <- dt[agentName == agent_name]

# Plot the evolution of the agent's belief and confidence
ggplot(data = singleAgentDT, aes(x = round)) +
  geom_line(aes(y = belief, colour = "Belief")) +
  geom_line(aes(y = confidence, colour = "Confidence")) +
  geom_line(aes(y = opinionClimate, colour = "Opinion Climate")) +
  scale_y_continuous(limits = c(-1, 1)) +
  labs(title = paste("Evolution of Beliefs and Confidence for", agent_name),
       x = "Round",
       y = "Value") +
  scale_colour_manual(values = c("Belief" = "blue", "Confidence" = "red", "Opinion Climate" = "green")) +
  theme_minimal()

# Calculate mean and median beliefs and confidences for all agents by round
summaryDT <- dt[,
  .(meanBelief = mean(belief), medianBelief = median(belief),
    meanConfidence = mean(confidence), medianConfidence = median(confidence)), by = round]

# Plot the evolution of mean and median beliefs and confidences
ggplot(data = summaryDT, aes(x = round)) +
  geom_line(aes(y = meanBelief, colour = "Mean Belief")) +
  geom_line(aes(y = medianBelief, colour = "Median Belief")) +
  geom_line(aes(y = meanConfidence, colour = "Mean Confidence")) +
  geom_line(aes(y = medianConfidence, colour = "Median Confidence")) +
  scale_y_continuous(limits = c(0, 1)) +
  labs(title = paste("Evolution of Mean and Median Beliefs and Confidence for", networkName),
       x = "Round",
       y = "Value") +
  scale_colour_manual(values = c("Mean Belief" = "blue", "Median Belief" = "green",
                                 "Mean Confidence" = "red", "Median Confidence" = "purple")) +
  theme_minimal()

# Discover diferent relationships
dt_static[, numberOfNeighborsPercentile := frank(numberOfNeighbors, ties.method = "average") / .N * 100]
dt_static[, beliefExpressionThresholdPercentile := frank(beliefExpressionThreshold, ties.method = "average") / .N * 100]
dt_static[, tolRadiusPercentile := frank(tolRadius, ties.method = "average") / .N * 100]
# Function to categorize percentiles
categorize_percentile <- function(percentile) {
  # Use ceiling to round up to the nearest 10, but subtract 1 first to make the lower bound exclusive for all but the first group
  group <- ifelse(percentile == 0, 10, ceiling((percentile - 1e-5) / 10) * 10)
  # Ensure that the group is at least the 10th percentile and does not exceed the 90th percentile
  pmin(pmax(group, 10), 100)
}

# Assuming dt_static is your data.table
# Calculate percentile ranks and categorize into percentile groups
dt_static[, numberOfNeighborsGroup := categorize_percentile(numberOfNeighborsPercentile)]
dt_static[, beliefExpressionThresholdGroup := categorize_percentile(beliefExpressionThresholdPercentile)]
dt_static[, tolRadiusGroup := categorize_percentile(tolRadiusPercentile)]

dt <- merge(dt, dt_static, by = "agentName")

# Create grouped animation plots
createAnimatedPlot <- function(dt, groupColumn, save_directory, title, width = 500, height = 500, dpi = 65, fps = 10) {
  # Convert the column to factor if it's not already
  dt[, (groupColumn) := factor(get(groupColumn))]

  p <- ggplot(dt, aes(x = .data[[groupColumn]], fill = factor(isSpeaking))) +
    geom_bar(position = "fill", aes(y = after_stat(count)/sum(after_stat(count)))) +
    scale_y_continuous(labels = percent_format()) +
    labs(x = "Percentile Group", y = "Percentage", fill = "Is Speaking",
         title = paste("Number of neighbors percentiles speaking at round:", "{frame_time}")) +
    scale_fill_manual(values = c("TRUE" = "#F8766D", "FALSE" = "#00BFC4"), name = "Is Speaking", labels = c("Yes", "No")) +
    theme_minimal(base_size = 14) +
    theme(
      plot.title = element_text(hjust = 0.5, size = 16, face = "bold"),
      axis.text.x = element_text(color = "grey30", size = 14),
      axis.text.y = element_text(color = "grey30", size = 14),
      axis.title.x = element_text(face = "bold", size = 14),
      axis.title.y = element_text(face = "bold", size = 14),
      legend.title = element_text(face = "bold", size = 12),
      legend.text = element_text(size = 12),
      legend.position = "bottom",
      legend.box.background = element_blank(),
      legend.key = element_blank(),
      panel.grid.major.x = element_blank(),
      panel.grid.minor.x = element_blank(),
      panel.grid.minor.y = element_blank(),
      panel.border = element_blank(),
      panel.background = element_blank()
    ) +
    guides(fill = guide_legend(reverse = TRUE)) +
    transition_time(round) +
    labs(title = paste("Percentiles of", title, "speaking at round: {frame_time}"))

  # Create the animation
  anim <- p + transition_time(round) +
    labs(title = paste("Percentiles of", title, "speaking at round: {frame_time}"))

  # Save the animation
  if (!dir.exists(save_directory)) {
    dir.create(save_directory, recursive = TRUE)
  }
  anim_save(paste0(save_directory, "/animated_plot_", groupColumn, ".gif"), animation = anim,
            width = width, height = height, units = "px", res = dpi, fps = fps)
}

print("Started rendering first 3 plots")
createAnimatedPlot(dt, "numberOfNeighborsGroup", save_directory, "number of neighbors") # save_directory
createAnimatedPlot(dt, "beliefExpressionThresholdGroup", save_directory, "expression threshold")
createAnimatedPlot(dt, "tolRadiusGroup", save_directory, "tolerance radius")
print("Finished rendering first 3 plots")

# Prepare the data for the animated plots
dt_long <- melt(dt, id.vars = c("round", "agentName"), measure.vars = c("belief", "confidence"),
                variable.name = "Metric", value.name = "Value")
dt_long[, Metric := factor(Metric, levels = c("belief", "confidence"), labels = c("Belief", "Confidence"))]

# Create the temporal directories
tempDir <- tempdir()
tempPlotDir <- file.path(tempDir, "round_plots")
dir.create(tempPlotDir)
rounds <- unique(dt$round)

print("Started rendering density and histogram plots")
# Loop through each round and generate a plot
for (r in rounds) {
  # Generate the graph for the single evolutions
  roundDT_long <- dt_long[round == r]

  # Density graph
  p_density <- ggplot(data = roundDT_long, aes(x = Value, fill = Metric)) +
    geom_density(alpha = 0.5) +
    facet_wrap(~ Metric, scales = "free_y", ncol = 2) +
    scale_x_continuous(limits = c(-0.05, 1.05)) +
    labs(x = "", y = "Density", title = paste("Round:", r)) +
    scale_fill_manual(values = c("Belief" = "blue", "Confidence" = "red")) +
    theme_light() +
    theme(legend.position = "none", plot.title = element_text(hjust = 0.5))

  # Histogram graph
  p_histogram <- ggplot(data = roundDT_long, aes(x = Value, fill = Metric)) +
    geom_histogram(binwidth = 0.05, alpha = 0.5, position = 'identity', closed = "left") +  # Use closed = "left" to include left edge
    facet_wrap(~ Metric, scales = "free_y", ncol = 2) +
    scale_x_continuous(limits = c(-0.05, 1.05)) +  # Expand limits slightly
    labs(x = "", y = "Count") +
    scale_fill_manual(values = c("Belief" = "blue", "Confidence" = "red")) +
    theme_light() +
    theme(legend.position = "none")

  p <- ggarrange(p_density, p_histogram, nrow = 2)


  # Save the plot to a file in the temporary directory
  ggsave(filename = paste0(tempPlotDir, "/density_round_", r, ".png"), plot = p, width = 500, height = 500,
         units = "px", dpi = 100, device = "png")
}

# Combine the saved density plots into a GIF as before
file_paths <- list.files(tempPlotDir, pattern = "density_round_.*\\.png$", full.names = TRUE)
file_paths <- file_paths[order(as.numeric(gsub(".*density_round_([0-9]+)\\.png$", "\\1", file_paths)))]

images <- image_read(file_paths)
gif <- image_animate(images, fps = 10)

image_write(gif, paste0(save_directory, "/distribution.gif"))

# Clean up the temporary files
unlink(tempPlotDir, recursive = TRUE)
print("Finished rendering density and histogram plots")

createAnimatedBeliefConfidencePlot <- function(dt, save_directory, width = 600, height = 600, dpi = 110, fps = 10) {
  # Convert `isSpeaking` to a factor
  dt[, isSpeaking := as.factor(isSpeaking)]

  # Prepare a summary table with means and medians for each round
  dt_summary <- dt[, .(meanBelief = mean(belief),
                       medianBelief = median(belief),
                       meanConfidence = mean(confidence),
                       medianConfidence = median(confidence)), by = round]

  p <- ggplot(data = dt, aes(x = belief, y = confidence)) +
    geom_point(aes(color = belief, size = numberOfNeighbors, shape = isSpeaking),
               position = position_jitter(width = 0.01, height = 0), alpha = 0.7) +
    scale_color_gradient(low = "blue", high = "red") +
    scale_size_continuous(range = c(2, 8)) +
    scale_shape_manual(values = c("TRUE" = 16, "FALSE" = 17)) +  # 16: circle, 17: triangle
    scale_x_continuous(limits = c(0, 1), name = "Belief") +
    scale_y_continuous(limits = c(0, 1), name = "Confidence") +
    labs(title = "Agent Belief vs Confidence Dynamics at round: {frame_time}") +
    theme_light() +
    theme(legend.position = "none", legend.title = element_blank(),
          plot.title = element_text(hjust = 0.5)) +
    transition_time(round) +
    enter_fade() +
    exit_fade() +
    ease_aes('linear')

  # Annotate each frame with the summary statistics for that round
  p <- p + geom_text(data = dt_summary, aes(x = Inf, y = Inf,
                                            label = sprintf("Mean Belief: %.2f\nMedian Belief: %.2f\nMean Confidence: %.2f\nMedian Confidence: %.2f",
                                                            meanBelief, medianBelief, meanConfidence, medianConfidence)),
                     hjust = 1, vjust = 1, size = 3.5, color = "black", inherit.aes = FALSE)

  # Save the animation
  if (!dir.exists(save_directory)) {
    dir.create(save_directory, recursive = TRUE)
  }

  anim_save(paste0(save_directory, "/animated_belief_confidence_plot.gif"), animation = p,
            width = width, height = height, units = "px", res = dpi, fps = fps)
}

print("Started rendering final graph")
createAnimatedBeliefConfidencePlot(dt, save_directory)

print(paste("Finished plotting, saved at:", save_directory))







#### Experiments

#final_round = dt[round == max(round), ]
min(dt$numberOfNeighbors)





