library(pacman)
pacman::p_load(
  RPostgres, data.table, ggplot2, ggthemes, scales, plotly, viridis, hrbrthemes,
  igraph, ggraph, dplyr, svglite, tidygraph, stringr, jsonlite
)

# Create a new DBI connection
conn <- dbConnect(RPostgres::Postgres(),
                  dbname = "promueva_new",
                  host = "localhost",
                  port = 5432,
                  user = "postgres",
                  password = "123facil123")

network <- "
SELECT id FROM
	networks
WHERE
	run_id = (SELECT id FROM runs ORDER BY id DESC LIMIT 1)
ORDER BY final_round LIMIT 1;
"
q0_res <- as.data.table(dbGetQuery(conn, network))
current_network <- ""
current_network <- q0_res$id

speaking_agents <- str_glue("
SELECT * FROM agent_states_speaking WHERE agent_id in (
    SELECT id
    FROM agents
    WHERE network_id = '{current_network}'
);
")

silent_agents <-  str_glue("
SELECT * FROM agent_states_silent WHERE agent_id in (
    SELECT id
    FROM agents
    WHERE network_id = '{current_network}'
);
")

neighbors <- str_glue("
SELECT * FROM neighbors WHERE source in (
    SELECT id
    FROM agents
    WHERE network_id = '{current_network}'
);
")

agents <- str_glue("
SELECT * FROM agents WHERE network_id = '{current_network}';
")
# Base 01920c94-853a-7000-9764-060412ab4ccb
# extended to 3 01920dcc-ab76-7000-921f-cbc78d33b8f5
# extended to 3 confidence 01920e15-2cc5-7000-a5a5-3e82c904f172
# Perpetual silence

# 01920db8-940e-7000-a5b8-7fa486907dbe

speaking_agents <- as.data.table(dbGetQuery(conn, speaking_agents))
silent_agents <- as.data.table(dbGetQuery(conn, silent_agents))
neighbors <- as.data.table(dbGetQuery(conn, neighbors))
agents <- as.data.table(dbGetQuery(conn, agents))
dbDisconnect(conn)

speaking_agents[, is_speaking := TRUE]
silent_agents[, is_speaking := FALSE]

agent_rounds <- rbind(speaking_agents, silent_agents)

data_table_result <- merge(agent_rounds, agents[, .(id, tolerance_radius, name)], by.x = "agent_id", by.y = "id")
setnames(data_table_result, "name", "agent_label")

filtered <- data_table_result[round <= 15]
filtered[, public_belief := as.numeric(gsub('.*:(.*)}.*', '\\1', state_data))]

melted_data <- melt(filtered,
                    id.vars = c("round", "agent_id", "agent_label", "is_speaking", "tolerance_radius"),
                    measure.vars = c("belief", "public_belief"),
                    variable.name = "belief_type", value.name = "value")

melted_data$belief_type <- factor(melted_data$belief_type,
                                  levels = c("belief", "public_belief"),
                                  labels = c("Opinion", "Public Opinion"))

# Extract agent numbers and create a numeric version for sorting
melted_data$agent_number <- as.numeric(gsub("Agent", "", melted_data$agent_label))

# Order the data by agent number and round
melted_data <- melted_data[order(melted_data$agent_number, melted_data$round), ]

# Create ordered levels for agent_label
ordered_agents <- paste0("Agent", sort(unique(melted_data$agent_number)))

# Create ordered factor for agent_label
melted_data$agent_label <- factor(melted_data$agent_label, levels = ordered_agents)

muted_palette <- c(
  "#4878D0",  # Blue
  "#EE854A",  # Orange
  "#6ACC64",  # Green
  "#D65F5F",  # Red
  "#956CB4",  # Purple
  "#8C613C",  # Brown
  "#DC7EC0",  # Pink
  "#45B4C2",  # Teal
  "#CEA93E",  # Gold
  "#7F7F7F"   # Gray
)

max_tol_radius <- max(data_table_result[round==0]$tolerance_radius)

# Ensure the palette has enough colors
if(length(muted_palette) < length(ordered_agents)) {
  muted_palette <- colorRampPalette(muted_palette)(length(ordered_agents))
}

# Create the plot
has_public_belief <- !anyNA(data_table_result$public_belief)

melted_data <- melted_data %>%
  mutate(shape_group = case_when(
    belief_type == "Public Opinion" ~ "Public",
    is_speaking == TRUE ~ "Non-silent",
    TRUE ~ "Silent"
  ))

# Create the base plot
p_publication <- ggplot(melted_data,
                        aes(x = round, y = value,
                            group = interaction(agent_label, belief_type),
                            color = agent_label,
                            fill = agent_label,
                            linetype = belief_type,
                            shape = shape_group)) +
  # Add semi-transparent ribbons for tolerance radius
  geom_ribbon(data = subset(melted_data, belief_type == "Opinion"),
              aes(ymin = pmax(value - tolerance_radius, 0),
                  ymax = pmin(value + tolerance_radius, 1)),
              alpha = 0.2,
              color = NA) +
  geom_line(size = 1, alpha = 0.8) +
  geom_point(size = 3, alpha = 0.8) +
  labs(x = "Time",
       y = "Opinion",
       color = "Agent",
       shape = "Silent Status") +
  scale_color_manual(values = setNames(muted_palette[seq_along(ordered_agents)], ordered_agents),
                     breaks = ordered_agents) +
  scale_fill_manual(values = setNames(muted_palette[seq_along(ordered_agents)], ordered_agents),
                    breaks = ordered_agents,
                    guide = "none") +
  scale_shape_manual(values = c("Public" = 16, "Non-silent" = 16, "Silent" = 17),
                     labels = c("Non-silent" = "Non-silent", "Silent" = "Silent"),
                     breaks = c("Non-silent", "Silent")) +
  scale_x_continuous(breaks = seq(0, max(melted_data$round), by = 5),
                     limits = c(-0.3, max(melted_data$round) + 0.3), expand = c(0, 0)) +
  scale_y_continuous(limits = c(-0.02, 1.02), breaks = seq(0, 1, 0.1), expand = c(0.01, 0)) +
  theme_minimal(base_size = 20) +
  theme(
    plot.title = element_text(size = 24, face = "bold", hjust = 0.5),
    plot.subtitle = element_text(size = 24, hjust = 0.5),
    axis.title = element_text(size = 24, face = "bold"),
    axis.text = element_text(size = 24),
    axis.ticks = element_line(color = "black", size = 0.5),
    axis.ticks.length = unit(0.25, "cm"),
    legend.title = element_text(size = 24, face = "bold"),
    legend.text = element_text(size = 24),
    legend.position = "right",
    legend.box = "vertical",
    panel.grid.major = element_blank(),
    panel.grid.minor = element_blank(),
    legend.key.size = unit(0.5, "cm"),  # New: Adjust legend key size
    legend.key.width = unit(1.5, "cm"),  # New: Adjust legend key width
    panel.border = element_rect(color = "black", fill = NA, size = 1),
    plot.margin = margin(1, 1, 1, 1, "cm")
  )

# Add linetype scale and legend only if public opinion exists
if (has_public_belief) {
  p_publication <- p_publication +
    scale_linetype_manual(values = c("Opinion" = "solid", "Public Opinion" = "dashed"),
                          guide = guide_legend(override.aes = list(linetype = c("solid", "dashed")))) +
    labs(linetype = "Plot")
} else {
  # If no public opinion, remove linetype from aesthetics
  p_publication <- p_publication +
    aes(linetype = NULL)
}

p_publication <- p_publication +
  guides(linetype = guide_legend(override.aes = list(linewidth = 1)))

print(p_publication)

# ggsave("publication.svg", plot = p_publication, width = 15, height = 10, dpi = 300)
# ggsave("testing.svg", plot = p_publication, width = 16, height = 10, units = "cm", dpi = 300)
# ggsave("testing.svg", plot = p_publication, width = 10, height = 6.5, units = "in", dpi = 300)
ggsave("testing.svg", plot = p_publication, width = 15, height = 10, dpi = 300)
# ggsave("testing.svg", plot = p_publication, width = 16, height = 10, units = "cm", dpi = 300)

filtered[, state_data := NULL]
keycol <-c("round","agent_label")
setorderv(filtered, keycol)
fwrite(filtered, "som-dissensus.csv")